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
    FILES("files", true, RestoreProcess.VOICE, FilesBackup.MAX_ENTRIES),
    /**
     * Skills connections (docs/plans/skills-connections-plan.md D7, §5.7; C10): connections.json as saved (addresses,
     * operations, switches, first-use answers, hand-offs' pins; never a key). Its own category, not inside Skills: a 1.1 to
     * 1.4 Robot refuses a whole known category at an entry name it doesn't know, and skips "connections" by name (its one
     * entry far under BackupCaps.UNKNOWN_ENTRY). Restored after Skills, whose steps name its ids. From a backup that isn't
     * yours, each arrives untrusted (RestoreSafety.connection: no first-use answers, no spoken yes, "Test it again", a
     * hand-off to pick again). Ordinal 13, right after Files: the voice log's category bits follow this order.
     */
    CONNECTIONS("connections", true, RestoreProcess.VOICE, 1),
    /**
     * Skills connections (D7, C10): each connection's secrets in their plain form ("conn/<id>", core ConnSecretsCodec's,
     * read by :voice's BackupSecrets straight into the backup's encryption). Off until you tick Keys and private codes,
     * whose row covers it ([coveredBy]); a Strong passphrase and "This backup is my own", as helper keys ([SENSITIVE]).
     * Restored only to a connection here of the same id and address it was saved for, where none is saved, with a fresh
     * Keystore key. Ordinal 14.
     */
    CONN_KEYS("conn_keys", false, RestoreProcess.VOICE, app.heyrobot.core.connect.ConnCheck.MAX_CONNECTIONS),
    /**
     * 1.4, Robot Lists (docs/plans/lists-plan.md §6, LS7): the lists in plain JSON ("lists.json", lists v1, the journal's
     * adds merged in first, tombstones included, inside the backup's own encryption, never sealed with the old phone's key)
     * and Settings › Lists ("lists_settings.json", not one of Settings' 8 files). Ordinal 15, after Connection keys: the voice log's
     * category bits follow this order, and a 1.1 to 1.3 Robot skips "lists" by name (each entry under
     * BackupCaps.UNKNOWN_ENTRY). Sealed again under this phone's key on restore; someone else's lists stay apart
     * (RestoreTaint.list).
     */
    LISTS("lists", true, RestoreProcess.VOICE, 2),
    /**
     * The everyday set (docs/plans/everyday-set-plan.md §2.4, D21): everyday_settings.json only ([EVERYDAY_FILE]), never one
     * of Settings' 8 files (a ninth would make an older reader refuse that whole category). Your music app's pin is left
     * out (BackupExport.everyday: trust is never in a backup), and the record of what went online is never in one (D11: it
     * describes this phone). Appended after the chain's last category: the voice log's category bits follow this order, and
     * a Robot without it skips "everyday" by name. Staged by :voice and written by the main app (RestoreApply), which
     * merges it over its own file (RestoreSafety.everydayFile).
     */
    EVERYDAY("everyday", true, RestoreProcess.MAIN, 1),
    /**
     * Robot to Robot (robot-to-robot-plan.md §2.7, D14; L12): pairings without keys, permissions and block list, what passed
     * between Robots ("links.json", core LinkBackupCodec, from :link) and Settings › Robot to Robot ("link_settings.json").
     * Appended after the chain's last category: the voice log's category bits follow this order, and a Robot without it skips
     * "links" by name. Restored only from a backup that is yours ([ONLY_YOURS], RestoreTaint.links), never as trust: levels
     * drop to Ask me first and "Answer while locked" comes back off (RestoreSafety.link, linkLevels). Staged by :voice; the
     * main app merges the settings and leaves links.json for :link (RestoreApply).
     */
    LINKS("links", true, RestoreProcess.MAIN, 2);

    /** Whether [name] is one of this category's entry names. */
    fun validName(name: String): Boolean = rule(name) != null

    /** The most bytes [name] may hold, or null when it isn't a name of this category. */
    fun maxBytes(name: String): Long? = rule(name)?.maxBytes

    /** The newest schema of [name] this Robot reads (0: a file without one), or null when it isn't a name of this category. */
    fun schema(name: String): Int? = rule(name)?.schema

    /**
     * The schema an entry records for [name] holding [data]: [schema]'s, except skills.json, which records its own "v"
     * (connections plan D6): a v1 file this Robot wrote stays readable by a 1.1 Robot's restore, and a v2 file is skipped
     * there as newer. A skills.json without a readable "v" records [schema]'s (its restore refuses it anyway).
     */
    fun schemaOf(name: String, data: ByteArray): Int? {
        val known = schema(name) ?: return null
        if (this != SKILLS || name != SkillStore.SKILLS) return known
        return SkillCodec.versionOf(String(data, Charsets.UTF_8))?.takeIf { it in 1..known } ?: known
    }

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
            EVERYDAY -> if (name == EVERYDAY_FILE) NameRule(BackupCaps.SMALL_FILE, app.heyrobot.core.everyday.EverydaySettingsCodec.VERSION) else null
            FILES -> when {
                name == FilesBackup.INDEX -> NameRule(app.heyrobot.core.files.FileLimits.MAX_INDEX, app.heyrobot.core.files.FileIndexCodec.VERSION)
                name == FilesBackup.SETTINGS -> NameRule(BackupCaps.SMALL_FILE, app.heyrobot.core.files.FilesSettingsCodec.VERSION)
                FilesBackup.blobId(name) != null -> NameRule(app.heyrobot.core.files.FileLimits.MAX_FILE, 0)
                else -> null
            }
            LISTS -> when (name) {
                LISTS_FILE -> NameRule(app.heyrobot.core.lists.ListsLimits.MAX_FILE.toLong(), app.heyrobot.core.lists.ListsCodec.VERSION)
                LISTS_SETTINGS -> NameRule(BackupCaps.SMALL_FILE, app.heyrobot.core.lists.ListsSettingsCodec.VERSION)
                else -> null
            }
            LINKS -> when (name) {
                LINKS_FILE -> NameRule(app.heyrobot.core.link.LinkBackupCodec.MAX_BYTES.toLong(), app.heyrobot.core.link.LinkBackupCodec.VERSION)
                LINK_SETTINGS -> NameRule(BackupCaps.SMALL_FILE, app.heyrobot.core.link.LinkSettingsCodec.VERSION)
                else -> null
            }
            CONNECTIONS -> if (name == CONNECTIONS_FILE) NameRule(app.heyrobot.core.connect.ConnectionCodec.MAX_BYTES.toLong(),
                app.heyrobot.core.connect.ConnectionCodec.VERSION) else null
            CONN_KEYS -> connOf(name)?.let { NameRule(app.heyrobot.core.connect.ConnSecretsCodec.MAX_BYTES.toLong(), app.heyrobot.core.connect.ConnSecretsCodec.VERSION) }
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
        /** 1.4, Lists: the lists' plain JSON (lists v1, at most 2 MiB) and Settings › Lists. */
        const val LISTS_FILE = "lists.json"
        const val LISTS_SETTINGS = app.heyrobot.core.lists.ListsSettingsCodec.FILE
        /** The everyday set's one entry: files/robot/everyday_settings.json. */
        const val EVERYDAY_FILE = app.heyrobot.core.everyday.EverydaySettingsCodec.FILE
        /** Robot to Robot: what :link hands over (no key is ever in it) and Settings › Robot to Robot. */
        const val LINKS_FILE = app.heyrobot.core.link.LinkBackupCodec.FILE
        const val LINK_SETTINGS = app.heyrobot.core.link.LinkSettingsCodec.FILE
        /** Connections' one entry: files/robot/connections.json (never skills_state.json's counts, never Test's facts). */
        const val CONNECTIONS_FILE = app.heyrobot.core.connect.ConnStore.FILE
        /** Connection keys' entries: "conn/<id>", one per connection with a key. */
        const val CONN_KEY = "conn/"

        /** Categories that need a Strong passphrase and "This backup is my own" on restore, and are off until you tick them. */
        val SENSITIVE: Set<BackupCategory> get() = setOf(KEYS, VOICEPRINT, CONN_KEYS)

        /**
         * What someone else's backup never restores: the sensitive categories, and pairings (D14), which take no tick of
         * their own and a passphrase of any strength: they hold no key.
         */
        val ONLY_YOURS: Set<BackupCategory> get() = SENSITIVE + LINKS

        /** The categories whose entries are keys, saved as they are read and deleted again when a restore is cut short. */
        val KEY_CATEGORIES: Set<BackupCategory> get() = setOf(KEYS, CONN_KEYS)

        /**
         * D7: the row on Back up now's list and the restore preview that a category is shown in: Connection keys in Keys and
         * private codes', so one tick covers both; every other category in its own.
         */
        fun coveredBy(c: BackupCategory): BackupCategory = if (c == CONN_KEYS) KEYS else c

        /** The rows the lists show, in order: every category but those another row covers. */
        val ROWS: List<BackupCategory> get() = entries.filter { coveredBy(it) == it }

        /** What a row's tick covers: [row] and the categories shown in it. */
        fun covers(row: BackupCategory): Set<BackupCategory> = entries.filterTo(LinkedHashSet()) { coveredBy(it) == row }

        /** A connection key's entry name → its connection id ("conn/c-ha0001" → "c-ha0001"), or null. */
        fun connOf(name: String): String? =
            name.takeIf { it.startsWith(CONN_KEY) }?.removePrefix(CONN_KEY)?.takeIf { app.heyrobot.core.skills.SkillStep.validConnId(it) }

        fun connKey(id: String): String = CONN_KEY + id

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
