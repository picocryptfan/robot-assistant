// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.BriefReminders
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.LookSettings
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.NoteStore
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.TranscriptFiles
import app.heyrobot.core.TranscriptIds
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.skills.SkillCodec
import app.heyrobot.core.skills.SkillStore
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.SearchService

// Pure. What a backup can hold, by category: each category's entry names (matched by pattern, so
// nothing can name a path outside its folder), its caps, the schema this Robot reads for each file, whether it is on by
// default, which process writes it back on restore, and the order of a restore. A refused entry refuses its whole
// category, never half of it.

/** Which app process writes a category back: main for its own settings files, :voice for the rest. */
enum class RestoreProcess { MAIN, VOICE }

enum class BackupCategory(
    /** The name in the file (an entry's "c"); never renamed. */
    val id: String,
    /** On in Back up now's list until you change it (Keys and private codes: off). */
    val defaultOn: Boolean,
    val writer: RestoreProcess,
    /** At most this many entries. */
    val maxEntries: Int,
) {
    // In restore order.
    SETTINGS("settings", true, RestoreProcess.MAIN, 8),
    PEOPLE("people", true, RestoreProcess.VOICE, 1),
    MEMORY("memory", true, RestoreProcess.VOICE, 1),
    HANDSFREE("handsfree", true, RestoreProcess.VOICE, 4),
    ROUTINES("routines", true, RestoreProcess.VOICE, 3),
    /**
     * 1.1, Phone lock (docs/plans/phone-lock-design.md §12): locks.json only, the locks and Settings › Phone lock's rows.
     * The lock code, the state and the record are in no category; restored locks arrive off (RestoreSafety.phoneLock).
     */
    PHONE_LOCK("lock", true, RestoreProcess.MAIN, 1),
    /**
     * 1.1, Skills (docs/plans/skills-1.1-plan.md §4): skills.json, restored after routines (Phone lock keeps its place right
     * after them). Never skills_state.json (the last runs). Someone else's skills arrive off (RestoreTaint.skill). The voice
     * log's category bits follow this order (1.1 has no release before it).
     */
    SKILLS("skills", true, RestoreProcess.VOICE, 2),
    NOTES("notes", true, RestoreProcess.VOICE, 20_000),
    HISTORY("history", true, RestoreProcess.VOICE, 50_000),
    TRANSCRIPTS("transcripts", true, RestoreProcess.VOICE, BackupCaps.MAX_TRANSCRIPTS * BackupCaps.TRANSCRIPT_FILES),
    KEYS("keys", false, RestoreProcess.VOICE, 65),
    /**
     * 1.1 (the user's decision of 15 Oct, replacing "never"): your voiceprint, me.voiceprint's plain form
     * (core VoiceprintCodec, decrypted by :voice only). The most sensitive category: off unless you tick it, its own
     * warning, a Strong passphrase (as Keys), unticked on the preview and restored only from a backup marked as your own,
     * never over one made on this phone.
     */
    VOICEPRINT("voiceprint", false, RestoreProcess.VOICE, 1),
    /**
     * 1.4, Robot Files (docs/plans/files-1.4-plan.md §2.8, F11): every file's plain bytes ("blob/<id>", inside the backup's
     * own encryption, never sealed with the old phone's key), the catalogue ("index.json", written after the bytes and
     * naming only the files whose bytes went in whole) and Settings › Files ("files_settings.json", not one of Settings'
     * 8 files: a ninth would make a 1.1 reader refuse that whole category). Appended last: the voice log's category bits
     * follow this order, and a 1.1 to 1.3 Robot skips "files" by name (each entry under BackupCaps.UNKNOWN_ENTRY). Restored
     * after People and Memory, which a file's links and your filing rules point at; sealed again under this phone's key.
     */
    FILES("files", true, RestoreProcess.VOICE, FilesBackup.MAX_ENTRIES);

    /** Whether [name] is one of this category's entry names. */
    fun validName(name: String): Boolean = rule(name) != null

    /** The most bytes [name] may hold, or null when it isn't a name of this category. */
    fun maxBytes(name: String): Long? = rule(name)?.maxBytes

    /** The newest schema of [name] this Robot reads (0: a file without one), or null when it isn't a name of this category. */
    fun schema(name: String): Int? = rule(name)?.schema

    /** Whether [name] holds JSON that StrictJson must accept before its store's codec reads it. */
    fun json(name: String): Boolean = name.endsWith(".json")

    private fun rule(name: String): NameRule? {
        if (name.isEmpty() || name.length > MAX_NAME || ".." in name || name.startsWith("/") || "\\" in name || name.any { it < ' ' }) return null
        return when (this) {
            SETTINGS -> SETTINGS_FILES[name]?.let { NameRule(BackupCaps.SETTINGS_FILE, it) }
            PEOPLE -> if (name == PEOPLE_FILE) NameRule(BackupCaps.PEOPLE_FILE, PeopleCodec.VERSION) else null
            MEMORY -> if (name == MEMORY_FILE) NameRule(BackupCaps.MEMORY_FILE, MemoryCodec.VERSION) else null
            HANDSFREE -> HANDSFREE_FILES[name]?.let { NameRule(BackupCaps.SMALL_FILE, it) }
            ROUTINES -> ROUTINE_FILES[name]?.let { NameRule(BackupCaps.SMALL_FILE, it) }
            SKILLS -> SKILL_FILES[name]?.let { NameRule(BackupCaps.SMALL_FILE, it) }
            PHONE_LOCK -> if (name == LOCK_FILE) NameRule(BackupCaps.LOCK_FILE, app.heyrobot.core.lock.LockCodec.VERSION) else null
            NOTES -> idOf(name)?.takeIf(NoteStore::validId)?.let { NameRule(BackupCaps.NOTE, NoteCodec.VERSION) }
            HISTORY -> idOf(name)?.takeIf(::conversationId)?.let { NameRule(BackupCaps.CONVERSATION, ConversationCodec.VERSION) }
            TRANSCRIPTS -> transcript(name)
            KEYS -> key(name)
            VOICEPRINT -> if (name == VOICEPRINT_FILE) NameRule(BackupCaps.VOICEPRINT, 0) else null
            FILES -> when {
                name == FilesBackup.INDEX -> NameRule(app.heyrobot.core.files.FileLimits.MAX_INDEX, app.heyrobot.core.files.FileIndexCodec.VERSION)
                name == FilesBackup.SETTINGS -> NameRule(BackupCaps.SMALL_FILE, app.heyrobot.core.files.FilesSettingsCodec.VERSION)
                FilesBackup.blobId(name) != null -> NameRule(app.heyrobot.core.files.FileLimits.MAX_FILE, 0)
                else -> null
            }
        }
    }

    private fun transcript(name: String): NameRule? {
        val slash = name.indexOf('/')
        if (slash < 0 || name.indexOf('/', slash + 1) >= 0) return null
        if (!TranscriptIds.valid(name.substring(0, slash))) return null
        return when (val file = name.substring(slash + 1)) {
            TranscriptFiles.META -> NameRule(BackupCaps.TRANSCRIPT_TEXT, TranscriptMeta.VERSION)
            TranscriptFiles.LOG, TranscriptFiles.SNAPSHOT -> NameRule(BackupCaps.TRANSCRIPT_TEXT, 0)
            TranscriptFiles.AUDIO -> NameRule(BackupCaps.RECORDING, 0)
            else -> if (SUMMARY.matches(file)) NameRule(BackupCaps.TRANSCRIPT_TEXT, 0) else null
        }
    }

    private fun key(name: String): NameRule? = when {
        name == MEMORY_CODES -> NameRule(BackupCaps.MEMORY_FILE, MemoryCodec.VERSION)
        name.startsWith(HELPER_KEY) && HELPER_ID.matches(name.removePrefix(HELPER_KEY)) -> NameRule(BackupCaps.KEY, 0)
        name.startsWith(SEARCH_KEY) && name.removePrefix(SEARCH_KEY) in SEARCH_SERVICES -> NameRule(BackupCaps.KEY, 0)
        else -> null
    }

    private class NameRule(val maxBytes: Long, val schema: Int)

    companion object {
        const val MAX_NAME = 96
        const val PEOPLE_FILE = "people.json"
        const val MEMORY_FILE = "memory.json"
        /** Phone lock's one entry: files/robot/lock/locks.json (never code.json or state.json). */
        const val LOCK_FILE = "lock/locks.json"
        /** Keys and private codes: Memory's items that hold a password, a PIN, a code or a card number. */
        const val MEMORY_CODES = "memory-codes.json"
        const val HELPER_KEY = "helper/"
        const val SEARCH_KEY = "search/"
        /**
         * The voiceprint's one entry: its plain bytes (VoiceprintCodec.encode, "RVP" 1), never the Keystore-sealed file,
         * whose name (me.voiceprint) no entry may have (RestoreTrustTest).
         */
        const val VOICEPRINT_FILE = "voiceprint.rvp"

        /** Categories that need a Strong passphrase and "This backup is my own" on restore, and are off until you tick them. */
        val SENSITIVE: Set<BackupCategory> get() = setOf(KEYS, VOICEPRINT)

        /**
         * The updater's settings file. A restore stages the backup's copy as it is ([UPDATES_STAGED], with [MINE_STAGED]
         * when the backup is yours) and the main app, the updater's process, keeps the safer values (RestoreSafety.updates).
         */
        const val UPDATES_FILE = UpdateSettings.FILE
        const val UPDATES_STAGED = UpdateSettings.FILE + ".backup"
        const val MINE_STAGED = "mine"
        /** Staged with Settings when :voice restored your voiceprint: the main app notes it is enrolled (meVoiceprint). */
        const val VOICEPRINT_STAGED = "voiceprint"

        /** The main app's settings files and the schema of each this Robot reads (0: the file has none). */
        val SETTINGS_FILES: Map<String, Int> = linkedMapOf(
            "settings.json" to RobotSettings.SCHEMA,
            LookSettings.FILE to LookSettings.VERSION,
            MemorySettings.FILE to MemorySettings.VERSION,
            MessagesFile.FILE to MessagesFile.VERSION,
            "web.json" to 0,
            "termux.json" to 0,
            UpdateSettings.FILE to UpdateSettings.VERSION,
            FeedbackPrefs.FILE to FeedbackPrefs.VERSION,
        )
        val HANDSFREE_FILES: Map<String, Int> = linkedMapOf(
            HandsFreeStore.DEVICES to DeviceMemory.VERSION,
            HandsFreeStore.HEADSET to HeadsetFile.VERSION,
            HandsFreeStore.CAR to CarFile.VERSION,
            HandsFreeStore.PLACES to Places.VERSION,
        )
        /** 1.1: the skills (phase B adds skill_settings.json); 1 MiB each, above skills.json's own 512 KiB. */
        val SKILL_FILES: Map<String, Int> = linkedMapOf(SkillStore.SKILLS to SkillCodec.VERSION,
            // Phase B: "Let Robot's model pick skills" (never skills_state.json: the samples start over after a restore).
            SkillStore.SETTINGS to app.heyrobot.core.skills.SkillSettingsCodec.VERSION)
        val ROUTINE_FILES: Map<String, Int> = linkedMapOf(
            "routines.json" to RoutineCodec.VERSION,
            "routine_settings.json" to RoutineSettingsCodec.VERSION,
            "reminders.json" to BriefReminders.VERSION,
        )

        /** Helper ids, as HelperConfig makes them ("claude", or letters, digits and "_"). */
        private val HELPER_ID = Regex("[a-z0-9_]{1,16}")
        /** The search services that take a key, by the name their key file has ("search_brave" → "brave"). */
        val SEARCH_SERVICES: Set<String> = SearchService.entries.mapNotNull { it.keyId?.removePrefix("search_") }.toSet()
        private val SUMMARY = Regex("summary-[0-9]{1,6}\\.json")
        /** History's ids, as ConversationStore.validId (app/sys/Stores.kt) checks them. */
        private val CONVERSATION_ID = Regex("^[A-Za-z0-9-]{1,64}$")

        fun conversationId(id: String): Boolean = CONVERSATION_ID.matches(id)

        fun of(id: String?): BackupCategory? = entries.firstOrNull { it.id == id }

        /** "<id>.json" → "<id>", else null. */
        fun idOf(name: String): String? = if (name.endsWith(".json") && '/' !in name) name.removeSuffix(".json") else null

        /** A transcript entry's transcript id ("<id>/meta.json" → "<id>"). */
        fun transcriptOf(name: String): String = name.substringBefore('/')
    }
}

/** The caps (estimates), in bytes unless said. */
object BackupCaps {
    const val KIB = 1024L
    const val MIB = 1024L * 1024
    const val GIB = 1024L * MIB
    /** A record's meta. */
    const val META = 4 * KIB
    const val MAX_RECORDS = 200_000
    /** The whole file's plaintext (the file is a little larger: 16 bytes a chunk, and the header). */
    const val MAX_TOTAL = 32 * GIB
    const val SETTINGS_FILE = 256 * KIB
    const val PEOPLE_FILE = 16 * MIB
    const val MEMORY_FILE = 5 * MIB
    const val SMALL_FILE = 1 * MIB
    /** Phone lock's locks.json: ten locks (LockCodec.MAX_BYTES). */
    const val LOCK_FILE: Long = app.heyrobot.core.lock.LockCodec.MAX_BYTES.toLong()
    const val NOTE = 1 * MIB
    const val CONVERSATION = 16 * MIB
    const val MAX_TRANSCRIPTS = 5_000
    /** meta.json, lines.jsonl, snapshot.json, audio.ogg and up to 100 summaries. */
    const val TRANSCRIPT_FILES = 104
    const val TRANSCRIPT_TEXT = 64 * MIB
    const val RECORDING = 1 * GIB
    const val KEY = 4 * KIB
    /** The voiceprint's plain form: a model id and at most VoiceprintCodec.MAX_DIM floats (a few KB). */
    const val VOICEPRINT = 64 * KIB
    /** An entry of a category this Robot doesn't know is read through (and skipped) up to this size. */
    const val UNKNOWN_ENTRY = 1 * GIB
}
