// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.BriefReminder
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.LookSettings
import app.heyrobot.core.PeopleEdit
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.People
import app.heyrobot.core.Person
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineCheck
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.WakePhrases
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.Addresses
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.i18n.BackupStrings
import app.heyrobot.core.i18n.Langs
import org.json.JSONObject
import java.util.UUID

// Pure. What a restore will do, category by category: the preview's plan, and the merges each
// category's writer applies. On a phone where a category is empty the backup's is simply written (FRESH), and whatever
// was added to it after the preview opened stays, as Add keeps it; where this phone has some, the preview offers Add to
// what's here (the default) or Replace what's here. Settings are never merged
// (on, they replace, through RestoreSafety); keys only ever go where this phone has none. Restoring the same backup twice
// in Add mode adds nothing the second time. Counts and codes only: the preview never shows an item, a person or a line.

enum class RestoreMode { FRESH, ADD, REPLACE }

/** Why a category won't be restored (each said in words on the preview and the result). */
enum class PlanSkip {
    /** F45: a transcription started since the file was copied in: Transcripts' Replace waits, so it can't delete it. */
    RECORDING,
    NOT_IN_BACKUP,
    NOT_CHOSEN,
    /** A file in it needs a newer Robot (SkipWhy.NEWER), or the category itself is unknown here. */
    NEEDS_NEWER,
    /** The file's entries for it broke its rules (SkipWhy.REFUSED) or its caps (SkipWhy.TOO_BIG). */
    REFUSED,
    TOO_BIG,
    /** "History is off on this phone: turn it on to restore your conversations." */
    HISTORY_OFF,
    /** Memory is off (after the Settings category): the preview offers "Turn Memory on", as Import does. */
    MEMORY_OFF,
    /** Keys and private codes come back only from a backup marked as yours. */
    NOT_YOURS,
    /** 1.1: this phone has a lock code, and the preview's Phone lock row didn't get it. */
    NEEDS_CODE,
    /** 1.1: this phone has a voiceprint of its own, which a restore never replaces (as a key entered here). */
    VOICEPRINT_HERE,
    /**
     * 1.4: Robot can't read the list of files on this phone (damaged, or a newer Robot's), which a restore never writes
     * over: Settings › Files' Start over first (files-1.4-plan.md §2.4).
     */
    FILES_UNREADABLE,
    /**
     * 1.4 F12: travel mode is on here: Robot Files is left as it is (a restore would replace or count the hidden drawers'
     * files) until you turn it off with your PIN.
     */
    FILES_TRAVEL,
    /**
     * 1.4, Lists: Robot can't read the lists on this phone (damaged, or a newer Robot's), which a restore never writes over:
     * Settings › Lists' Start over first (lists-plan.md §4.5).
     */
    LISTS_UNREADABLE,
    /**
     * C10: Robot can't read the connections on this phone (connections.json damaged, or a newer Robot's), which a restore
     * never writes over: Connections and Connection keys are left out.
     */
    CONNECTIONS_UNREADABLE,
    /** seal-people-fix 1: People on this phone can't be opened just now (busy, or the phone locked): left as it is. */
    PEOPLE_NOT_NOW,
    /** seal-people-fix 1: People on this phone is damaged, or a newer Robot's: never written over, until People's Start over. */
    PEOPLE_UNREADABLE,
    /** H3: Robot can't read the private events on this phone (their key gone, damaged, a newer Robot's): never written over. */
    CALENDAR_UNREADABLE,
}

/** seal-people-fix 1: whether this phone's People can take a restore ([PeopleLoad] in three words). */
enum class PeopleHere { OK, NOT_NOW, UNREADABLE;
    companion object {
        fun of(load: app.heyrobot.core.PeopleLoad): PeopleHere = when (load) {
            is app.heyrobot.core.PeopleLoad.Ok -> OK
            app.heyrobot.core.PeopleLoad.NotNow -> NOT_NOW
            else -> UNREADABLE
        }
    }
}

/**
 * 1.4, Lists: whether this phone's lists can take a restore. [KEY_GONE]: their key is gone, so they can never be read again:
 * Lists restores only as Replace, and the file here is kept aside (lists.enc.bad, as Start over keeps it), never deleted.
 * [UNREADABLE]: damaged or a newer Robot's: left out until Start over.
 */
enum class ListsHere { OK, KEY_GONE, UNREADABLE }

/**
 * 1.4: whether this phone's Robot Files can take a restore. [KEY_GONE]: robot_files_v1 is gone, so the files here can never
 * be read again: Files restores only as Replace (the preview says how many go). [UNREADABLE]: the list can't be read
 * (damaged, a newer Robot's): left out until Start over.
 */
enum class FilesHere { OK, KEY_GONE, UNREADABLE,
    /** F12: travel mode is on: Files is left out of a restore (PlanSkip.FILES_TRAVEL). */
    TRAVEL }

/** What this phone has now, by category (0: nothing: the backup's is simply written), and its own History and Memory switches. */
data class PhoneHas(val counts: Map<BackupCategory, Int>, val historyOn: Boolean = true, val memoryOn: Boolean = true,
    /** 1.1: a Phone lock code is set on this phone (state.json's codeSet). */
    val lockCode: Boolean = false,
    /** 1.4: whether this phone's Robot Files can take a restore ([FilesHere]); its count is the files here either way. */
    val files: FilesHere = FilesHere.OK,
    /** 1.4, Lists: whether this phone's lists can take a restore ([ListsHere]); its count is the lists here (1 when unreadable). */
    val lists: ListsHere = ListsHere.OK,
    /** C10: this phone's connections.json can take a restore (false: damaged or a newer Robot's, never written over). */
    val connections: Boolean = true,
    /** seal-people-fix 1: this phone's People can take a restore; its count is 1 when it can't (something is here). */
    val people: PeopleHere = PeopleHere.OK) {
    fun empty(c: BackupCategory): Boolean = (counts[c] ?: 0) == 0
}

/** The preview's choice for a category: restore it or not, and Replace rather than Add. */
data class CategoryChoice(val restore: Boolean = true, val replace: Boolean = false,
    /** 1.1: the preview's Phone lock row took this phone's lock code (the code pad, in :lock). */
    val codeOk: Boolean = false)

data class CategoryPlan(val category: BackupCategory, val mode: RestoreMode, val skip: PlanSkip?, val inBackup: Long) {
    val restores: Boolean get() = skip == null
}

/** The whole plan: one line per category, in restore order, and the file's categories this Robot can't restore, by name. */
class RestorePlan(val categories: List<CategoryPlan>, val mine: Boolean, val skippedByName: List<CategorySkip>) {
    fun of(c: BackupCategory): CategoryPlan = categories.first { it.category == c }
    val restoring: List<BackupCategory> get() = categories.filter { it.restores }.map { it.category }
    override fun toString(): String = "RestorePlan(mine=$mine, ${categories.map { "${it.category.id}:${it.skip ?: it.mode}" }})"  // l10n: not text (toString for logs and tests)
}

object RestorePlanner {
    /**
     * The preview's defaults: every category the file holds, Add to what's here, Keys and the voiceprint only once you choose
     * them. 1.4: Files too, unless this phone's files can't be read again (their key gone, [phone]): restoring Files then
     * deletes them, so it waits for your tick, beside its line saying how many go (files-1.4-plan.md §2.4, §2.8).
     */
    fun defaults(backup: VerifiedBackup, phone: PhoneHas? = null): Map<BackupCategory, CategoryChoice> =
        BackupCategory.entries.associateWith { c ->
            val goneHere = c == BackupCategory.FILES && phone != null && phone.files == FilesHere.KEY_GONE && !phone.empty(c)
            CategoryChoice(restore = backup.has(c) && c !in BackupCategory.SENSITIVE && !goneHere)
        }

    /**
     * The plan for [backup] on this phone ([phone]) with the preview's [choices] and [mine] ("This backup is my own").
     * History is restored only if saving History is on after the Settings category, Memory only if Memory is.
     */
    fun plan(backup: VerifiedBackup, phone: PhoneHas, choices: Map<BackupCategory, CategoryChoice>, mine: Boolean): RestorePlan {
        val settingsToo = backup.has(BackupCategory.SETTINGS) && choices[BackupCategory.SETTINGS]?.restore == true
        val historyOn = if (settingsToo && "settings.json" in backup.settings) RobotSettings.fromJson(backup.settings["settings.json"]).saveHistory && (mine || phone.historyOn) else phone.historyOn
        val memoryOn = if (settingsToo && MemorySettings.FILE in backup.settings) MemorySettings.fromJson(backup.settings[MemorySettings.FILE]).on && (mine || phone.memoryOn) else phone.memoryOn
        val lines = BackupCategory.entries.map { c ->
            val choice = choices[c] ?: CategoryChoice(restore = false)
            val inBackup = backup.found[c]?.entries ?: 0
            val mode = when {
                c == BackupCategory.KEYS || c == BackupCategory.CONN_KEYS -> RestoreMode.ADD
                c == BackupCategory.VOICEPRINT -> RestoreMode.FRESH
                phone.empty(c) -> RestoreMode.FRESH
                // A pairing here is never replaced by one from a file (LinkBackupStore.restore).
                c == BackupCategory.LINKS -> RestoreMode.ADD
                // The everyday set's one settings file is merged field by field over this phone's, as Settings' are.
                c == BackupCategory.SETTINGS || c == BackupCategory.EVERYDAY || c == BackupCategory.HOME || c == BackupCategory.OPEN_APPS || c == BackupCategory.HEADSUP || choice.replace -> RestoreMode.REPLACE
                // Files whose key is gone can't take anything added to them: Replace is the only way.
                c == BackupCategory.FILES && phone.files == FilesHere.KEY_GONE -> RestoreMode.REPLACE
                // Lists whose key is gone: the same; the file here is kept aside.
                c == BackupCategory.LISTS && phone.lists == ListsHere.KEY_GONE -> RestoreMode.REPLACE
                else -> RestoreMode.ADD
            }
            val refused = backup.skipped.firstOrNull { it.category == c }?.why
            val skip = when {
                refused == SkipWhy.NEWER -> PlanSkip.NEEDS_NEWER
                refused == SkipWhy.REFUSED -> PlanSkip.REFUSED
                refused == SkipWhy.TOO_BIG -> PlanSkip.TOO_BIG
                !backup.has(c) -> PlanSkip.NOT_IN_BACKUP
                !choice.restore -> PlanSkip.NOT_CHOSEN
                c in BackupCategory.ONLY_YOURS && !mine -> PlanSkip.NOT_YOURS
                c == BackupCategory.VOICEPRINT && !phone.empty(c) -> PlanSkip.VOICEPRINT_HERE
                c == BackupCategory.HISTORY && !historyOn -> PlanSkip.HISTORY_OFF
                c == BackupCategory.MEMORY && !memoryOn -> PlanSkip.MEMORY_OFF
                c == BackupCategory.PHONE_LOCK && phone.lockCode && !choice.codeOk -> PlanSkip.NEEDS_CODE
                c == BackupCategory.FILES && phone.files == FilesHere.UNREADABLE -> PlanSkip.FILES_UNREADABLE
                c == BackupCategory.FILES && phone.files == FilesHere.TRAVEL -> PlanSkip.FILES_TRAVEL
                c == BackupCategory.LISTS && phone.lists == ListsHere.UNREADABLE -> PlanSkip.LISTS_UNREADABLE
                (c == BackupCategory.CONNECTIONS || c == BackupCategory.CONN_KEYS) && !phone.connections -> PlanSkip.CONNECTIONS_UNREADABLE
                c == BackupCategory.PEOPLE && phone.people == PeopleHere.NOT_NOW -> PlanSkip.PEOPLE_NOT_NOW
                c == BackupCategory.PEOPLE && phone.people == PeopleHere.UNREADABLE -> PlanSkip.PEOPLE_UNREADABLE
                else -> null
            }
            CategoryPlan(c, mode, skip, inBackup)
        }
        return RestorePlan(lines, mine, backup.skipped.filter { it.category == null || it.why == SkipWhy.NEWER })
    }
}

// ------------------------------------------------------------------------------------------------- the merges

/** This phone's settings files as their stores read them now. */
data class SettingsNow(
    val settings: RobotSettings = RobotSettings(),
    val look: LookSettings = LookSettings(),
    val memory: MemorySettings = MemorySettings(),
    val messages: MessagesFile = MessagesFile(),
    val web: WebSettings = WebSettings(),
    val termux: TermuxSettings = TermuxSettings(),
    val updates: UpdateSettings = UpdateSettings(),
    val feedback: FeedbackPrefs = FeedbackPrefs(),
) {
    companion object {
        /**
         * This phone's settings for a restore in :voice, which never reads the updater's (no class there refers to it,
         * verify_apk.sh): updates.json is staged as it is and the main app merges it (RestoreSafety.updates).
         */
        fun withoutUpdates(settings: RobotSettings, look: LookSettings, memory: MemorySettings, messages: MessagesFile, web: WebSettings,
                           termux: TermuxSettings, feedback: FeedbackPrefs): SettingsNow =
            SettingsNow(settings, look, memory, messages, web, termux, UpdateSettings(), feedback)
    }
}

/**
 * The settings files to stage for the main app (by name, as text its stores read), the guarded settings kept at this
 * phone's value, and the restored settings.json (for the categories after it: which helpers exist, History, Memory).
 */
class SettingsRestored(val files: Map<String, String>, val kept: List<SaferField>, val settings: RobotSettings) {
    override fun toString(): String = "SettingsRestored(${files.keys}, kept=${kept.size})"  // l10n: not text (toString for logs and tests)
}

/** People after a restore, and where each person of the file landed (their id here). */
class PeopleRestored(val people: List<Person>, val landed: Map<String, String>, val added: Int, val updated: Int, val skipped: Int) {
    override fun toString(): String = "PeopleRestored(people=${people.size}, added=$added, updated=$updated, skipped=$skipped)"  // l10n: not text (toString for logs and tests)
}

/** Files of a many-files category: the ids to write from the backup, and the ids of this phone's to delete (Replace). */
data class FilesPlan(val write: Set<String>, val delete: Set<String>, val keptHere: Int)

/** Why a restored routine arrives off. */
enum class RoutineOff { NOT_YOURS, DEVICE_GONE }

class RoutinesRestored(
    val routines: List<Routine>,
    val settings: RoutineSettings,
    val reminders: List<BriefReminder>,
    /** Routines that arrive off (by their id here), and why. */
    val off: Map<String, RoutineOff>,
    val added: Int,
    val kept: List<SaferField>,
) {
    override fun toString(): String = "RoutinesRestored(routines=${routines.size}, added=$added, off=${off.size})"  // l10n: not text (toString for logs and tests)
}

/** 1.1, Skills after a restore (plan §4): the list to write, how many came from the file, and those that arrive off. */
class SkillsRestored(val skills: List<app.heyrobot.core.skills.Skill>, val added: Int, val off: Int,
    /** Phase B: skill_settings.json to write (the file's, its guarded switch kept at this phone's unless [mine]), or null: as it is. */
    val settings: app.heyrobot.core.skills.SkillSettings? = null, val kept: List<SaferField> = emptyList()) {
    override fun toString(): String = "SkillsRestored(skills=${skills.size}, added=$added, off=$off)"  // l10n: not text (toString for logs and tests)
}

class HandsFreeRestored(
    val devices: DeviceMemory,
    /** Where each device of the file landed: "dev-3" → "dev-7". */
    val landed: Map<String, String>,
    val headset: HeadsetFile,
    val car: CarFile,
    val places: Places,
    val added: Int,
    val kept: List<SaferField>,
) {
    override fun toString(): String = "HandsFreeRestored(devices=${devices.devices.size}, added=$added)"  // l10n: not text (toString for logs and tests)
}

/** Why a key of the file isn't saved here. */
enum class KeySkip { NOT_YOURS, NO_SUCH_HELPER, ALREADY_HERE,
    /** C10: no connection of that id here after the restore, or none in the backup to say what the key was for. */
    NO_SUCH_CONNECTION,
    /** C10: the connection here of that id reaches another address than the one the key was saved for. */
    OTHER_ADDRESS,
    /** C10: the key holds other parts than the connection here needs (a token where it takes a topic too). */
    OTHER_PARTS }

/**
 * C10, Skills connections after a restore: the list to write, how many came from the file, the connections whose key on
 * this phone must go first (Replace: gone, or now reaching another address), and the guarded fields kept safer.
 */
class ConnectionsRestored(val connections: List<app.heyrobot.core.connect.Connection>, val added: Int, val dropKeys: Set<String>,
                          val kept: List<SaferField>,
                          /** skills-backup-b: the ids whose connection came from the backup (added, or the whole list replaced). */
                          val restored: Set<String> = emptySet()) {
    override fun toString(): String = "ConnectionsRestored(connections=${connections.size}, added=$added, dropKeys=${dropKeys.size})"  // l10n: not text (toString for logs and tests)
}

/** skills-backup-b: the trusted keys to write (null: leave skill_keys.json), and each MCP tools file to write, by connection id. */
class SkillTrustRestored(val keys: List<app.heyrobot.core.skills.TrustedKey>?, val mcp: Map<String, String>) {
    override fun toString(): String = "SkillTrustRestored(keys=${keys?.size}, mcp=${mcp.size})" // l10n: not text (toString for logs and tests)
}

data class KeysPlan(val save: List<String>, val skipped: Map<String, KeySkip>) {
    /** R2: the keys that didn't come because nothing here goes with them; one already here is kept on purpose. */
    val noPlace: Int get() = skipped.values.count { it != KeySkip.ALREADY_HERE }
}

object RestoreMerge {
    /**
     * The mode a merge applies. FRESH was planned for a category empty when the preview opened: while it still is, the
     * backup's is simply written; once something was added since, that stays, as Add keeps it.
     */
    private fun settled(mode: RestoreMode, emptyNow: Boolean): RestoreMode = if (mode == RestoreMode.FRESH && !emptyNow) RestoreMode.ADD else mode

    /**
     * moments-backup: reminders for a moment. Replace (or Fresh while still empty) writes the backup's; Add keeps this phone's
     * and adds the backup's not here yet (the same words for the same moment once, an id taken here given a new one) up to
     * [app.heyrobot.core.MomentReminders.MAX]. A wait held on the old phone (a car-off's two minutes) starts over.
     */
    fun moments(have: List<app.heyrobot.core.MomentReminder>, file: List<app.heyrobot.core.MomentReminder>, mode: RestoreMode,
                newId: () -> String = { UUID.randomUUID().toString() }): List<app.heyrobot.core.MomentReminder> {
        val max = app.heyrobot.core.MomentReminders.MAX
        // moments-backup-c: Replace goes through the same loop, so the same words for the same moment come once there too.
        val out = if (settled(mode, have.isEmpty()) == RestoreMode.ADD) ArrayList(have) else ArrayList()
        val ids = out.mapTo(HashSet()) { it.id }
        for (r in file.map { it.copy(heldUntil = null) }) {
            if (out.size >= max) break
            if (out.any { it.moment == r.moment && it.text.equals(r.text, ignoreCase = true) }) continue
            val named = if (r.id in ids) r.copy(id = generateSequence(newId).first { it !in ids }) else r
            ids += named.id
            out += named
        }
        return out
    }

    /**
     * settings.json and the other settings files: each file the backup has, decoded by its store's
     * codec (which migrates an older one), the guarded fields kept at this phone's values unless [mine], trust dropped,
     * this phone's own values kept, the wake phrases cleaned, and messages.json's People ids pointed where each landed
     * ([people]). A file the backup lacks isn't touched.
     */
    fun settings(phone: SettingsNow, file: Map<String, String>, mine: Boolean, people: (String) -> String?): SettingsRestored {
        val out = LinkedHashMap<String, String>()
        val kept = LinkedHashSet<SaferField>()
        fun <T> take(g: Guarded<T>): T { kept += g.kept; return g.value }
        var settings = phone.settings
        for ((name, text) in file) {
            out[name] = when (name) {
                "settings.json" -> {
                    val s = take(RestoreSafety.settings(phone.settings, RobotSettings.fromJson(text), mine))
                    settings = s.copy(wakePhrases = WakePhrases.sanitize(s.wakePhrases).ifEmpty { phone.settings.wakePhrases })
                    settings.toJson().toString()
                }
                LookSettings.FILE -> take(RestoreSafety.look(phone.look, LookSettings.fromJson(text))).toJson()
                MemorySettings.FILE -> take(RestoreSafety.memory(phone.memory, MemorySettings.fromJson(text), mine)).toJson()
                MessagesFile.FILE -> take(RestoreSafety.messages(phone.messages, RestoreRefs.messages(MessagesFile.fromJson(text), people), mine)).toJson()
                "web.json" -> take(RestoreSafety.web(phone.web, WebSettings.fromJson(obj(text)), mine)).toJson().toString()
                "termux.json" -> take(RestoreSafety.termux(phone.termux, TermuxSettings.fromJson(obj(text)), mine)).toJson().toString()
                UpdateSettings.FILE -> take(RestoreSafety.updates(phone.updates, UpdateSettings.fromJson(text), mine)).toJson()
                FeedbackPrefs.FILE -> take(RestoreSafety.feedback(phone.feedback, FeedbackPrefs.fromJson(text), mine)).toJson()
                else -> continue
            }
        }
        return SettingsRestored(out, kept.toList(), settings)
    }

    private fun obj(text: String): JSONObject? = try { JSONObject(text) } catch (_: Exception) { null }

    /**
     * People: Replace (or a phone with nobody) takes the file's list, its ids kept; Add merges it in
     * ([PeopleEdit.merge]: the same person shares a number, an email or an XMPP address and the names agree). Unless
     * [mine], everyone from the file is unconfirmed, someone already here stays as confirmed (or not) as they were, and
     * someone you confirmed never changes: an entry that matches them lands on them only when it adds nothing to them;
     * one that would add a number, an email, an address or a name stays apart, unconfirmed, and what the file links to it
     * follows it there (Mom's number with an email she never had doesn't make that email Mom's). An id of the file that
     * someone here already has, without being the same person, gets a new one.
     */
    fun people(have: List<Person>, file: List<Person>, mode: RestoreMode, mine: Boolean, now: Long, gateway: String = PhoneNumbers.DEFAULT_GATEWAY,
        newId: () -> String = PeopleStore::newId): PeopleRestored {
        val incoming = file.map { RestoreTaint.person(it, mine) }
        if (settled(mode, have.isEmpty()) != RestoreMode.ADD) {
            val list = incoming.filter { PeopleStore.validId(it.id) }.distinctBy { it.id }.map(People::clean).take(People.MAX_PEOPLE)
            return PeopleRestored(list, list.associate { it.id to it.id }, list.size, 0, file.size - list.size)
        }
        val ids = have.mapTo(HashSet()) { it.id }
        val renamed = incoming.map { p -> if (p.id in ids) p.copy(id = generateSequence(newId).first { it !in ids }) else p }
        val merged = PeopleEdit.merge(have, renamed, now, gateway)
        val before = have.associateBy { it.id }
        // Merging confirms the person it lands on; from a file that isn't yours, they stay as they were (and someone
        // nothing else changed for stays exactly as they were, so a second restore changes nothing).
        fun kept(p: Person, old: Person?): Person {
            old ?: return p
            val q = if (mine) p else p.copy(confirmed = old.confirmed)
            return if (q.copy(updatedAt = old.updatedAt) == old) old else q
        }
        val landed = HashMap<String, String>()
        // The file's entries that landed on someone you confirmed and would change them, by their place in the file, and
        // those of them that are that person (they matched them as they are here, not as an earlier entry changed them).
        val apart = ArrayList<Int>()
        val match = HashMap<Int, String>()
        file.forEachIndexed { i, p ->
            val at = merged.landed.getOrNull(i) ?: return@forEachIndexed
            val you = before[at]?.takeIf { !mine && it.confirmed }
            if (you == null) { landed[p.id] = at; return@forEachIndexed }
            val alone = PeopleEdit.merge(listOf(you), listOf(renamed[i]), now, gateway)
            if (alone.added == 0 && alone.updated == 0) { landed[p.id] = you.id; return@forEachIndexed }
            apart += i
            if (alone.added == 0) match[i] = you.id
        }
        var people = merged.people.map { p -> before[p.id]?.takeIf { !mine && it.confirmed } ?: kept(p, before[p.id]) }
        if (apart.isNotEmpty()) {
            // Merged only with those you haven't confirmed (the file's own, or learned here), and unconfirmed.
            val others = people.filter { !it.confirmed }
            val was = others.associateBy { it.id }
            val m = PeopleEdit.merge(others, apart.map { renamed[it] }, now, gateway)
            val changed = m.people.filter { it.id in was }.associate { it.id to kept(it.copy(confirmed = false), was[it.id]) }
            val joining = m.people.filter { it.id !in was }.map { it.copy(confirmed = false) }
            people = people.map { changed[it.id] ?: it } + joining.take((People.MAX_PEOPLE - people.size).coerceAtLeast(0))
            val present = people.mapTo(HashSet()) { it.id }
            apart.forEachIndexed { j, i -> (m.landed.getOrNull(j)?.takeIf { it in present } ?: match[i])?.let { landed[file[i].id] = it } }
        }
        val skipped = merged.skipped + apart.count { file[it].id !in landed }
        return PeopleRestored(people, landed, people.count { it.id !in before }, people.count { p -> before[p.id]?.let { it != p } ?: false }, skipped)
    }

    /**
     * Notes or History by id, with when each was last changed: Add writes the ones this phone hasn't and
     * those whose copy in the file is newer; Replace writes every one of the file and deletes this phone's others; a
     * phone with none simply takes them all (FRESH is Add: one written since the preview stays). R14: [replaceYours] false
     * (Notes, from someone else's backup: they carry no mark), Add never writes over what this phone has, newer or not.
     */
    fun byUpdated(have: Map<String, Long>, file: Map<String, Long>, mode: RestoreMode, replaceYours: Boolean = true): FilesPlan = when (mode) {
        RestoreMode.ADD, RestoreMode.FRESH -> {
            val write = file.filter { (id, t) -> have[id]?.let { replaceYours && t > it } ?: true }.keys
            FilesPlan(write, emptySet(), have.size - have.keys.count { it in write })
        }
        RestoreMode.REPLACE -> FilesPlan(file.keys, have.keys - file.keys, 0)
    }

    /** Transcripts by id: Add (and FRESH) leaves one already here alone; Replace deletes this phone's and writes the file's. */
    /**
     * F45: whether Transcripts wait: a Replace deletes every folder here, and [heartbeat] (.session.json), [freshMs] old or
     * less at [now], says one is being recorded into. Add and a first restore delete nothing, so they carry on.
     */
    fun transcriptsWait(mode: RestoreMode, heartbeat: String?, now: Long, freshMs: Long): Boolean =
        mode == RestoreMode.REPLACE && app.heyrobot.core.SessionHeartbeat.fromJson(heartbeat)?.let { now - it.beatAt < freshMs } == true

    // fix-seal-transcripts-d, Low 1: [keep] (a newer Robot's, TranscriptStore.newer) is never deleted or written over by Replace.
    fun transcripts(have: Set<String>, file: Set<String>, mode: RestoreMode, keep: Set<String> = emptySet()): FilesPlan = when (mode) {
        RestoreMode.ADD, RestoreMode.FRESH -> FilesPlan(file - have, emptySet(), have.size)
        RestoreMode.REPLACE -> FilesPlan(file - keep, have - keep, have.count { it in keep })
    }

    /**
     * Routines. Add: each routine of the file is added unless the same routine is already here
     * (its trigger, steps, conditions, output and lock), so a second restore adds nothing; one whose name is taken gets
     * "(restored)" (BackupStrings.restoredName) and a new id. Replace: the file's routines and brief settings (guarded). A routine whose headset or car
     * didn't land arrives off; unless [mine], every one arrives off. Brief reminders, which the brief says as Robot's own
     * words, come only from a backup marked yours: Replace takes its, Add (and FRESH) adds the ones this phone hasn't;
     * from someone else's, this phone's stay as they are.
     */
    fun routines(have: List<Routine>, haveSettings: RoutineSettings, haveReminders: List<BriefReminder>, file: List<Routine>,
                 fileSettings: RoutineSettings?, fileReminders: List<BriefReminder>?, mode: RestoreMode, mine: Boolean,
                 devices: (String) -> String?, newId: () -> String = { UUID.randomUUID().toString() }, langs: Langs = Langs.EN): RoutinesRestored {
        val off = LinkedHashMap<String, RoutineOff>()
        fun prepared(r: Routine): Routine {
            val moved = RestoreRefs.routine(r, devices)
            val t = RestoreTaint.routine(moved ?: r.copy(enabled = false), mine)
            if (moved == null && r.enabled) off[t.id] = RoutineOff.DEVICE_GONE
            else if (!mine && r.enabled) off[t.id] = RoutineOff.NOT_YOURS
            return t
        }
        val incoming = file.filter { it.id != BriefRoutine.ID && RoutineCheck.validId(it.id) }
        val settings = fileSettings?.let { RestoreSafety.routineSettings(haveSettings, it, mine) }
        val theirs = if (mine) fileReminders.orEmpty() else emptyList()
        val reminders = (haveReminders + theirs.filter { f -> haveReminders.none { it.text.equals(f.text, ignoreCase = true) && it.day == f.day } })
            .takeLast(app.heyrobot.core.BriefReminders.MAX)
        val applied = settled(mode, have.isEmpty())
        if (applied != RestoreMode.ADD) {
            val list = incoming.distinctBy { it.id }.take(RoutineCheck.MAX_ROUTINES).map(::prepared)
            // R9: a backup with no reminders file says nothing about them: this phone's stay, as with every other absent file.
            val brief = if (applied == RestoreMode.REPLACE && mine && fileReminders != null) fileReminders.takeLast(app.heyrobot.core.BriefReminders.MAX) else reminders
            return RoutinesRestored(list, settings?.value ?: haveSettings, brief, off, list.size, settings?.kept.orEmpty())
        }
        val out = ArrayList(have)
        val ids = have.mapTo(HashSet()) { it.id }
        var added = 0
        for (r in incoming) {
            if (out.size >= RoutineCheck.MAX_ROUTINES) break
            val moved = RestoreRefs.routine(r, devices) ?: r
            if (out.any { same(it, moved) }) continue
            var named = r
            if (out.any { it.name.equals(r.name, ignoreCase = true) }) named = r.copy(name = restoredName(r.name, langs))
            if (named.id in ids) named = named.copy(id = generateSequence(newId).first { it !in ids && RoutineCheck.validId(it) })
            ids += named.id
            out += prepared(named)
            added++
        }
        return RoutinesRestored(out, haveSettings, reminders, off, added, emptyList())
    }

    private fun same(a: Routine, b: Routine): Boolean =
        a.trigger == b.trigger && a.steps == b.steps && a.conditions == b.conditions && a.output == b.output && a.lock == b.lock

    /**
     * 1.1, Skills (plan §4). Replace: the file's skills, at most 100. Add: the file's skills this phone hasn't (the same
     * name and steps), a taken name (or other name) given "(restored)" and its other names that are taken left out, a taken
     * id a new one, still at most 100. Unless [mine], each arrives off (RestoreTaint.skill).
     */
    fun skills(have: List<app.heyrobot.core.skills.Skill>, file: List<app.heyrobot.core.skills.Skill>, mode: RestoreMode, mine: Boolean,
               newId: () -> String, langs: Langs = Langs.EN, haveSettings: app.heyrobot.core.skills.SkillSettings = app.heyrobot.core.skills.SkillSettings(),
               fileSettings: app.heyrobot.core.skills.SkillSettings? = null): SkillsRestored {
        val max = app.heyrobot.core.skills.SkillCheck.MAX_SKILLS
        fun prepared(s: app.heyrobot.core.skills.Skill) = RestoreTaint.skill(s, mine)
        val offBy = { s: app.heyrobot.core.skills.Skill -> !mine && s.level != app.heyrobot.core.skills.SkillLevel.OFF }
        if (settled(mode, have.isEmpty()) != RestoreMode.ADD) {
            val list = file.distinctBy { it.id }.take(max)
            // Phase B: the file's skill_settings.json, its switch kept at this phone's when it is looser and the backup isn't yours.
            val settings = fileSettings?.let { RestoreSafety.skillSettings(haveSettings, it, mine) }
            return SkillsRestored(list.map(::prepared), list.size, list.count(offBy), settings?.value, settings?.kept.orEmpty())
        }
        val out = ArrayList(have)
        var added = 0
        var off = 0
        for (s in file) {
            if (out.size >= max) break
            if (out.any { it.name.equals(s.name, ignoreCase = true) && it.steps == s.steps && it.params == s.params }) continue
            val taken = out.flatMap { it.keys }.toSet()
            var named = s.copy(namesEn = s.namesEn.filter { app.heyrobot.core.skills.SkillKey.of(it) !in taken },
                namesFr = s.namesFr.filter { app.heyrobot.core.skills.SkillKey.of(it) !in taken })
            if (app.heyrobot.core.skills.SkillKey.of(s.name) in taken) named = named.copy(name = restoredSkillName(s.name, langs))
            if (out.any { it.id == named.id }) named = named.copy(id = generateSequence(newId).first { id -> out.none { it.id == id } && app.heyrobot.core.skills.SkillCheck.validId(id) })
            if (!app.heyrobot.core.skills.SkillCheck.valid(named) || named.keys.any { it in taken }) continue
            if (offBy(named)) off++
            out += prepared(named)
            added++
        }
        // Add keeps this phone's setting, as routines do.
        return SkillsRestored(out, added, off)
    }

    /** "Heading home (restored)" in the restore's language, within a skill name's 40 characters. */
    private fun restoredSkillName(name: String, langs: Langs): String {
        val room = app.heyrobot.core.skills.SkillCheck.MAX_NAME - BackupStrings.restoredName("name" to "").render(langs).length
        return BackupStrings.restoredName("name" to name.take(room).trimEnd()).render(langs)
    }

    /** "Good morning (restored)" in the restore's language ([langs]), within a routine name's length. */
    private fun restoredName(name: String, langs: Langs): String {
        val room = RoutineCheck.MAX_NAME - BackupStrings.restoredName("name" to "").render(langs).length
        return BackupStrings.restoredName("name" to name.take(room).trimEnd()).render(langs)
    }

    /**
     * Headset, car and places. Add: each device of the file is added unless this phone remembers it (by
     * its whole address, or by its kind, name and address tail when one has no whole address), with a new number; Home
     * and Work only where this phone has none; this phone's headset and car settings stay. Replace (or a phone with
     * none): the file's, guarded, its devices numbered from this phone's next number as Add numbers them, so none takes
     * the number of a device remembered here before and a routine here never waits for another device instead; one this
     * phone remembers too keeps its number. Unless [mine], "only you can hear it" is asked again for each headset.
     */
    fun handsfree(haveDevices: DeviceMemory, haveHeadset: HeadsetFile, haveCar: CarFile, havePlaces: Places, file: Map<String, String>,
                  mode: RestoreMode, mine: Boolean): HandsFreeRestored {
        val fileDevices = file[HandsFreeStore.DEVICES]?.let { (DeviceMemory.decode(it) as? app.heyrobot.core.output.DeviceFile.Ok)?.memory }
        val fileHeadset = file[HandsFreeStore.HEADSET]?.let(HeadsetFile::decode)
        val fileCar = file[HandsFreeStore.CAR]?.let(CarFile::decode)
        val filePlaces = file[HandsFreeStore.PLACES]?.let(Places::decode)
        val kept = ArrayList<SaferField>()
        if (settled(mode, haveDevices.devices.isEmpty() && havePlaces.home == null && havePlaces.work == null) != RestoreMode.ADD) {
            val landed = HashMap<String, String>()
            val devices = fileDevices?.let { m ->
                var next = haveDevices.nextNumber
                val taken = HashSet<Int>()
                val list = m.devices.map { d ->
                    val same = haveDevices.devices.firstOrNull { it.number !in taken && sameDevice(it, d) }
                    val n = same?.number ?: next++
                    taken += n
                    RestoreTaint.device(d, mine, same).copy(number = n).also { landed[d.id] = it.id }
                }
                DeviceMemory(list, maxOf(next, m.nextNumber))
            } ?: haveDevices
            val headset = fileHeadset?.let { RestoreSafety.headset(haveHeadset, it, mine) }?.also { kept += it.kept }?.value ?: haveHeadset
            val car = fileCar?.let { RestoreSafety.car(haveCar, it, mine) }?.also { kept += it.kept }?.value ?: haveCar
            val places = filePlaces?.let { RestoreSafety.places(havePlaces, it, mine, add = false) }?.also { kept += it.kept }?.value ?: havePlaces
            return HandsFreeRestored(devices, landed, headset, car, places, devices.devices.size, kept)
        }
        val out = ArrayList(haveDevices.devices)
        var next = haveDevices.nextNumber
        val landed = HashMap<String, String>()
        var added = 0
        for (d in fileDevices?.devices.orEmpty()) {
            val here = out.firstOrNull { sameDevice(it, d) }
            if (here != null) { landed[d.id] = here.id; continue }
            if (out.size >= DeviceMemory.MAX) continue
            val n = RestoreTaint.device(d, mine).copy(number = next++)
            out += n
            landed[d.id] = n.id
            added++
        }
        val places = filePlaces?.let { RestoreSafety.places(havePlaces, it, mine, add = true) }?.also { kept += it.kept }?.value ?: havePlaces
        return HandsFreeRestored(DeviceMemory(out, next), landed, haveHeadset, haveCar, places, added, kept)
    }

    private fun sameDevice(a: RememberedDevice, b: RememberedDevice): Boolean = when {
        a.address != null && b.address != null -> a.address == b.address
        else -> a.role == b.role && a.family == b.family && a.tail == b.tail && Addresses.sameName(a.name, b.name)
    }

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2.8): the backup's [file] index (every entry's bytes checked) on a phone whose
     * index is [have], in [mode] (FilesMerge.plan: Add by id and SHA-256, Replace deleting this phone's first, someone else's
     * to Inbox unless [mine]). [people]: where each People id of the file landed.
     */
    fun files(have: app.heyrobot.core.files.FileIndex, file: app.heyrobot.core.files.FileIndex, mode: RestoreMode, mine: Boolean, now: Long,
              people: (String) -> String?): FilesRestored = FilesMerge.plan(have, file, mode, mine, now, people)

    /**
     * 1.4, Robot Lists (lists-plan.md §6, LS7): the backup's [file] on this phone's lists [have], in [mode], by
     * ListsMerge.add. Add: a list of yours here and in the file merged by item id (the higher rev wins; equal, the later
     * upd; equal, this phone's), a list only in the file added. Replace: this phone's lists go first, the file's are taken
     * as Add takes them onto none. Unless [mine], the file's lists arrive apart from yours (RestoreTaint.list): marked from
     * a backup, no role, every item by "backup", never merged into yours. [roleName]: a role's name in the app's language
     * (someone else's Shopping arrives as a list called that). Over 2 MiB: TooBig, nothing changes.
     */
    fun lists(have: app.heyrobot.core.lists.ListsFile, file: app.heyrobot.core.lists.ListsFile, mode: RestoreMode, mine: Boolean,
              ids: () -> app.heyrobot.core.lists.ListsId, roleName: (app.heyrobot.core.lists.ListRole) -> String): app.heyrobot.core.lists.ListsMergeResult =
        app.heyrobot.core.lists.ListsMerge.add(if (mode == RestoreMode.REPLACE) app.heyrobot.core.lists.ListsFile() else have, file, mine, ids, roleName)

    /**
     * C10, Skills connections (plan §5.7, D7). Add: each connection of the file whose id this phone hasn't is added (at most
     * 20); one whose id is here stays exactly as it is, with its key, so a second restore adds nothing and no address of
     * the file reaches a key of yours (FRESH is Add once one was added since the preview opened). Replace (and a phone with
     * none): the file's connections, ids kept, and this phone's
     * key goes for every connection that goes or now reaches another address ([sameOrigin]); from a backup that isn't
     * yours, every key here goes. Ids are never changed, so the skills' steps still name theirs. Each connection the file
     * brings passes [RestoreSafety.connection]: from a backup that isn't yours it arrives untrusted (no first-use answers,
     * no spoken yes, "Test it again", a hand-off to pick again, at most 30 requests a minute).
     *
     * C12's L1: [keyIds] are the connections with a key folder on this phone (ConnKeyFiles.ids). One without a connection
     * here (a stray: a connection connections.json dropped on read, say) goes too, in every mode and from any backup,
     * before the connections arrive, so it never becomes the key of a connection the file brings under the same id. A
     * stray is usable by nothing, as Start over already treats it.
     */
    fun connections(have: List<app.heyrobot.core.connect.Connection>, file: List<app.heyrobot.core.connect.Connection>, mode: RestoreMode,
                    mine: Boolean, keyIds: Set<String>): ConnectionsRestored {
        val max = app.heyrobot.core.connect.ConnCheck.MAX_CONNECTIONS
        val strays = keyIds - have.mapTo(HashSet()) { it.id }
        val kept = LinkedHashSet<SaferField>()
        fun prepared(f: app.heyrobot.core.connect.Connection, here: app.heyrobot.core.connect.Connection?) =
            RestoreSafety.connection(here, f, mine).also { kept += it.kept }.value
        if (settled(mode, have.isEmpty()) != RestoreMode.ADD) {
            val byId = have.associateBy { it.id }
            val list = file.distinctBy { it.id }.take(max).map { f -> prepared(f, byId[f.id]?.takeIf { sameOrigin(it, f) }) }
            val keep = if (mine) list.filter { l -> byId[l.id]?.let { sameOrigin(it, l) } == true }.map { it.id }.toSet() else emptySet()
            return ConnectionsRestored(list, list.size, have.map { it.id }.toSet() - keep + strays, kept.toList(), list.mapTo(LinkedHashSet()) { it.id })
        }
        val out = ArrayList(have)
        val brought = LinkedHashSet<String>()
        for (f in file) {
            if (out.size >= max) break
            if (out.any { it.id == f.id }) continue
            out += prepared(f, null)
            brought += f.id
        }
        return ConnectionsRestored(out, brought.size, strays, kept.toList(), brought)
    }

    /**
     * skills-backup-b: the keys you trust and the MCP tool choices, only from a backup that is yours (null and none
     * otherwise, whatever the plan said). Keys: this phone's first, then the file's it doesn't have, at most
     * SkillShare.MAX_TRUSTED; null when the file has none (skill_keys.json left as it is). A tools file only for a connection
     * this restore brought ([restoredConns], ConnectionsRestored.restored), never for one kept as it was here.
     */
    fun skillTrust(have: List<app.heyrobot.core.skills.TrustedKey>, file: List<app.heyrobot.core.skills.TrustedKey>?, mcp: Map<String, String>,
                   restoredConns: Set<String>, mine: Boolean): SkillTrustRestored {
        if (!mine) return SkillTrustRestored(null, emptyMap())
        val keys = file?.let { f ->
            val out = LinkedHashMap<String, app.heyrobot.core.skills.TrustedKey>()
            for (k in have + f) if (out.size < app.heyrobot.core.skills.SkillShare.MAX_TRUSTED) out.putIfAbsent(k.fingerprint, k)
            out.values.toList()
        }
        return SkillTrustRestored(keys, mcp.filterKeys { it in restoredConns })
    }

    /**
     * C10: [a] and [b] reach the same place the same way: kind, address (a secret address's origin), whether the address is
     * the key, how the key goes, the routing server's kind, a hand-off's app. A key saved for one is a key for the other.
     */
    fun sameOrigin(a: app.heyrobot.core.connect.Connection, b: app.heyrobot.core.connect.Connection): Boolean =
        a.kind == b.kind && a.address == b.address && a.secretAddress == b.secretAddress && a.auth == b.auth && a.routing == b.routing &&
            a.handOff?.pkg == b.handOff?.pkg

    /**
     * C10, Connection keys (D7): only from a backup marked yours; only for a connection [here] (after the Connections
     * category, or as this phone has them) with the same id and [sameOrigin] as the backup's connection the key was saved
     * for ([file]: the backup's connections, null when it has none), so a key never goes to an address it wasn't saved
     * for; only with exactly the parts that connection needs ([keys]: each key's parts by connection id); and only where
     * this phone has none ([hasKey], by id): a key you entered here is never replaced. [KeysPlan.save] holds entry names.
     */
    fun connectionKeys(keys: Map<String, Set<app.heyrobot.core.connect.SecretPart>>, file: List<app.heyrobot.core.connect.Connection>?,
                       here: List<app.heyrobot.core.connect.Connection>, hasKey: (String) -> Boolean, mine: Boolean): KeysPlan {
        val save = ArrayList<String>()
        val skipped = LinkedHashMap<String, KeySkip>()
        for ((id, parts) in keys) {
            val name = BackupCategory.connKey(id)
            val was = file?.firstOrNull { it.id == id }
            val now = here.firstOrNull { it.id == id }
            val why = when {
                !mine -> KeySkip.NOT_YOURS
                was == null || now == null -> KeySkip.NO_SUCH_CONNECTION
                !sameOrigin(was, now) -> KeySkip.OTHER_ADDRESS
                parts != now.secrets -> KeySkip.OTHER_PARTS
                hasKey(id) -> KeySkip.ALREADY_HERE
                else -> null
            }
            if (why == null) save += name else skipped[name] = why
        }
        return KeysPlan(save, skipped)
    }

    /**
     * Keys: only from a backup marked yours, only for a helper that exists after the Settings category
     * ([helpers]) and only where this phone has no key ([hasKey]): a key you entered here is never replaced.
     */
    fun keys(file: List<String>, helpers: Set<String>, hasKey: (String) -> Boolean, mine: Boolean): KeysPlan {
        val save = ArrayList<String>()
        val skipped = LinkedHashMap<String, KeySkip>()
        for (k in file) {
            if (k == BackupCategory.MEMORY_CODES) continue
            val why = when {
                !mine -> KeySkip.NOT_YOURS
                k.startsWith(BackupCategory.HELPER_KEY) && k.removePrefix(BackupCategory.HELPER_KEY) !in helpers -> KeySkip.NO_SUCH_HELPER
                hasKey(k) -> KeySkip.ALREADY_HERE
                else -> null
            }
            if (why == null) save += k else skipped[k] = why
        }
        return KeysPlan(save, skipped)
    }
}
