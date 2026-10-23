// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.LookSettings
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.NoteStore
import app.heyrobot.core.PeopleFile
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.SessionHeartbeat
import app.heyrobot.core.TranscriptFiles
import app.heyrobot.core.TranscriptIds
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.backup.BackupCaps
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupException
import app.heyrobot.core.backup.BackupExport
import app.heyrobot.core.backup.BackupFormat
import app.heyrobot.core.backup.BackupHeader
import app.heyrobot.core.backup.BackupKey
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.OpenProblem
import app.heyrobot.core.backup.PhoneHas
import app.heyrobot.core.backup.PlanSkip
import app.heyrobot.core.backup.RestoreMerge
import app.heyrobot.core.backup.RestoreMode
import app.heyrobot.core.backup.RestorePlan
import app.heyrobot.core.backup.RestoreProcess
import app.heyrobot.core.backup.RestoreReader
import app.heyrobot.core.backup.RestoreRefs
import app.heyrobot.core.backup.RestoreResult
import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.backup.RestoreTaint
import app.heyrobot.core.backup.SettingsNow
import app.heyrobot.core.backup.StagedFiles
import app.heyrobot.core.backup.VerifiedBackup
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemoryRestore
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import app.heyrobot.lock.LockStore
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.MemoryFiles
import app.heyrobot.sys.MemorySettingsStore
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.Paths
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.RestoreFiles
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.SkillFiles
import app.heyrobot.core.skills.SkillCodec
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillIds
import app.heyrobot.core.skills.SkillStore
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.TermuxStore
import app.heyrobot.sys.VoiceprintFiles
import app.heyrobot.sys.WebStore
import app.heyrobot.core.backup.FilesBackup
import app.heyrobot.core.backup.FilesHere
import app.heyrobot.core.backup.FilesMerge
import app.heyrobot.core.backup.FilesRestored
import app.heyrobot.core.backup.JournalState
import app.heyrobot.core.backup.NotNow
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileProblem
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.files.FilesSettingsCodec
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.parse.FileType
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.RobotFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

// :VOICE PROCESS (docs/plans/backup-1.1-design.md §7). A restore: the picked file copied in (encrypted) to
// files/robot/restore/incoming, its header and passphrase checked, the whole file verified before anything is written,
// the preview's facts about this phone, then each chosen category written whole through the journal (core
// RestoreJournal): People and Memory through their stores' own atomic writes, Headset and car and Routines staged and
// written under their stores' locks, Notes, History and Transcripts staged and moved in, keys and the voiceprint saved
// straight to new Keystore keys (BackupSecrets). The main app's settings files and Phone lock's locks.json are staged here
// and written by the main app at its next start (sys/RestoreApply), which the restart brings. 1.4: Robot Files' files sealed
// again under this phone's robot_files_v1 into restore/files/ during the same pass, then committed through its vault
// (FileStore.restoreIn). Nothing of the file's content is logged: the voice log gets codes (events 241, 242).

internal class RestoreSession(private val ctx: Context,
                              /** 1.4: this process's Robot Files (a test's own vault with a software key). */
                              private val files: () -> FilesVault = { RobotFiles.vault(ctx) }) : AutoCloseable {
    var header: BackupHeader? = null
        private set
    private var key: BackupKey? = null
    var verified: VerifiedBackup? = null
        private set

    private val incoming get() = RestoreFiles.incoming(ctx)
    private fun open(): InputStream = incoming.inputStream().buffered(BackupFormat.CHUNK)

    /** Why the file can't be opened before its passphrase: a problem of the file, too little space, a recording, a lock. */
    sealed class Refused {
        class File(val problem: OpenProblem) : Refused()
        class NoSpace(val bytes: Long) : Refused()
        data object Recording : Refused()
        data object Locked : Refused()
    }

    /** A transcription is running now (its heartbeat is fresh): a restore waits for it to stop. */
    fun recording(now: Long = System.currentTimeMillis()): Boolean =
        SessionHeartbeat.fromJson(readOrNull(File(transcriptsDir(), SessionHeartbeat.FILE)))?.let { now - it.beatAt < HEARTBEAT_MS } == true

    /** Steps 2 and 3: the header read, the free space checked, the encrypted file copied in. Null: copied. */
    fun copyIn(uri: Uri, progress: (Float) -> Unit): Refused? {
        if (Assistant.locked(ctx)) return Refused.Locked
        if (recording()) return Refused.Recording
        val cr = ctx.contentResolver
        val size = try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
        } catch (_: Throwable) { -1L }
        if (size > BackupCaps.MAX_TOTAL + BackupCaps.MAX_TOTAL / 4000) return refused(OpenProblem.TOO_BIG)
        try {
            val input = cr.openInputStream(uri) ?: return refused(OpenProblem.NOT_A_BACKUP)
            input.use { i ->
                val start = ByteArray(BackupFormat.HEADER_SIZE)
                var n = 0
                while (n < start.size) { val k = i.read(start, n, start.size - n); if (k < 0) return refused(OpenProblem.NOT_A_BACKUP); n += k }
                val h = try { RestoreReader.header(start) } catch (x: BackupException) { return refused(x.problem) }
                // The copy, what it unpacks to, and room to spare (EST).
                if (size > 0) {
                    val need = 2 * size + SPARE
                    val free = StatFs(ctx.filesDir.path).availableBytes
                    if (free < need) return Refused.NoSpace(need - free)
                }
                RestoreFiles.dir(ctx).mkdirs()
                incoming.outputStream().buffered(BackupFormat.CHUNK).use { out ->
                    out.write(start)
                    val buf = ByteArray(BackupFormat.CHUNK)
                    var total = start.size.toLong()
                    while (true) {
                        val k = i.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        total += k
                        if (total > BackupCaps.MAX_TOTAL + BackupCaps.MAX_TOTAL / 4000) { incoming.delete(); return refused(OpenProblem.TOO_BIG) }
                        if (size > 0) progress((total.toFloat() / size).coerceAtMost(1f))
                    }
                }
                header = h
            }
        } catch (t: Throwable) {
            RLog.w("restore_copy", t)
            incoming.delete()
            return refused(OpenProblem.NOT_A_BACKUP)
        }
        return null
    }

    private fun refused(p: OpenProblem): Refused {
        VoiceLog.add(VoiceEvent.RESTORE_OPEN, p.id, header?.format ?: 0)
        return Refused.File(p)
    }

    /** Step 4: the passphrase (Argon2id, then chunk 0 decides). Null: opened. */
    fun unlock(passphrase: CharSequence): OpenProblem? = attempt {
        key?.close()
        key = RestoreReader.unlock(::open, header!!, passphrase)
    }

    /** Step 5: the whole file read and checked; nothing of yours is written. Null: verified. */
    fun verify(progress: (Long) -> Unit): OpenProblem? = attempt {
        verified = RestoreReader.verify(::open, header!!, key!!, progress)
        VoiceLog.add(VoiceEvent.RESTORE_OPEN, 0, header!!.format)
    }

    private fun attempt(block: () -> Unit): OpenProblem? = try {
        block()
        null
    } catch (x: BackupException) {
        VoiceLog.add(VoiceEvent.RESTORE_OPEN, x.problem.id, header?.format ?: 0)
        x.problem
    } catch (t: Throwable) {
        RLog.w("restore_open", t)
        VoiceLog.add(VoiceEvent.RESTORE_OPEN, OpenProblem.DAMAGED.id, header?.format ?: 0)
        OpenProblem.DAMAGED
    }

    // ------------------------------------------------------------------------------------------------- this phone now

    private fun transcriptsDir() = File(ctx.filesDir, TranscriptFiles.DIR)
    private fun ids(dir: File, valid: (String) -> Boolean): List<File> =
        dir.listFiles { f -> f.isFile && BackupCategory.idOf(f.name)?.let(valid) == true }.orEmpty().toList()
    private fun haveNotes(): Map<String, Long> = ids(NotesFiles.dir(ctx), NoteStore::validId)
        .mapNotNull { f -> readOrNull(f)?.let(NoteCodec::fromJson)?.let { it.id to it.updated } }.toMap()
    private fun haveHistory(): Map<String, Long> = ids(Paths.conversations(ctx), BackupCategory::conversationId)
        .mapNotNull { f -> readOrNull(f)?.let(ConversationCodec::fromJson)?.let { it.id to it.updated } }.toMap()
    private fun haveTranscripts(): Set<String> = transcriptsDir().listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) }.orEmpty().map { it.name }.toSet()

    /** What this phone has now, by category, and its History and Memory switches and lock code (the preview's PhoneHas). */
    fun phone(): PhoneHas {
        val s = SettingsStore.load(ctx)
        val memory = BackupSources.memory(ctx)
        val hf = HandsFreeFiles.store(ctx)
        val counts = mapOf(
            BackupCategory.SETTINGS to if (File(Paths.dir(ctx), "settings.json").isFile) 1 else 0, // l10n: not text (a file name)
            BackupCategory.PEOPLE to PeopleFiles.store(ctx).list().size,
            BackupCategory.MEMORY to (memory?.items?.size ?: 1),
            BackupCategory.HANDSFREE to hf.devices().devices.size + listOfNotNull(hf.places().home, hf.places().work).size,
            BackupCategory.ROUTINES to RoutineFiles.store(ctx).routines().size,
            BackupCategory.SKILLS to SkillFiles.store(ctx).skills().size,
            BackupCategory.PHONE_LOCK to (LockStore.read(ctx).ok?.locks?.size ?: 0),
            BackupCategory.NOTES to ids(NotesFiles.dir(ctx), NoteStore::validId).size,
            BackupCategory.HISTORY to ids(Paths.conversations(ctx), BackupCategory::conversationId).size,
            BackupCategory.TRANSCRIPTS to haveTranscripts().size,
            BackupCategory.VOICEPRINT to if (VoiceprintFiles.saved(ctx)) 1 else 0,
        )
        // 1.4, Robot Files: its files (counted from their sealed sizes when the list can't be read: no key needed), and
        // whether it can take a restore (its key gone: Replace only; its list unreadable: left out until Start over).
        val fv = files()
        val load = fv.load()
        // F12: travel mode on, Files is left out of a restore and its count is what shows (never the hidden drawers' files).
        val travel = app.heyrobot.core.files.TravelMode.active(FilesSettingsStore.current(ctx))
        val filesCount = when {
            travel -> load.current?.let { app.heyrobot.core.files.TravelMode.shown(it, FilesSettingsStore.current(ctx)).entries.size } ?: 0
            load is IndexLoad.Loaded -> load.index.entries.size
            load == IndexLoad.Empty -> 0
            else -> fv.usage().files
        }
        val filesHere = if (travel) FilesHere.TRAVEL else when (load) {
            IndexLoad.KeyGone -> FilesHere.KEY_GONE
            IndexLoad.Unreadable, is IndexLoad.TooNew -> FilesHere.UNREADABLE
            else -> FilesHere.OK
        }
        // Phone lock's code is verified by :lock's code pad only (CategoryChoice.codeOk); this only says whether one is set.
        return PhoneHas(counts + (BackupCategory.FILES to filesCount), historyOn = s.saveHistory, memoryOn = MemorySettingsStore.current(ctx).on,
            lockCode = LockStore.readState(ctx).codeSet, files = filesHere)
    }

    private fun settingsNow(): SettingsNow {
        fun text(name: String) = readOrNull(File(Paths.dir(ctx), name))
        return SettingsNow.withoutUpdates(
            settings = SettingsStore.load(ctx),
            look = LookSettings.fromJson(text(LookSettings.FILE)),
            memory = MemorySettings.fromJson(text(MemorySettings.FILE)),
            messages = MessagesFile.fromJson(text(MessagesFile.FILE)),
            web = WebStore.load(ctx),
            termux = TermuxStore.load(ctx),
            feedback = FeedbackPrefs.fromJson(text(FeedbackPrefs.FILE)),
        )
    }

    /** How many guarded settings keep this phone's value for [mine] (the preview's "Kept safer"). */
    fun keptSafer(mine: Boolean): Int {
        val v = verified ?: return 0
        val settings = try { RestoreMerge.settings(settingsNow(), v.settings - BackupCategory.UPDATES_FILE, mine) { it }.kept.size } catch (_: Throwable) { 0 }
        // 1.4: Settings › Files' safer values too.
        val files = v.filesSettings?.let { RestoreSafety.filesSettings(FilesSettingsStore.current(ctx), it.read, mine).kept.size } ?: 0
        return settings + files
    }

    // ------------------------------------------------------------------------------------------------------ the restore

    /** Step 7: writes [plan]'s categories, each whole or not at all, and says how each ended. [progress]: the category now. */
    fun apply(plan: RestorePlan, progress: (BackupCategory) -> Unit): RestoreResult {
        running = true
        try {
            return write(plan, progress)
        } finally {
            running = false
        }
    }

    private fun write(plan: RestorePlan, progress: (BackupCategory) -> Unit): RestoreResult {
        val v = verified!!
        val k = key!!
        val mine = plan.mine
        val now = System.currentTimeMillis()
        val j = RestoreFiles.journal(ctx)
        val out = LinkedHashMap<BackupCategory, CategoryOutcome>()
        for (line in plan.categories) if (!line.restores && line.skip != PlanSkip.NOT_IN_BACKUP) out[line.category] = CategoryOutcome.Skipped(line.skip!!)
        val restoring = plan.restoring.toSet()
        fun mode(c: BackupCategory) = plan.of(c).mode
        // Checked again before the first write.
        if (Assistant.locked(ctx)) {
            for (c in restoring) out[c] = CategoryOutcome.Locked
            return finish(out, v, mine, 0)
        }
        // FX1 (files-1.4-review.md finding 10): a Files commit an earlier restore left waiting for an unlock is finished
        // first (never overwritten); still waiting, this restore's Files waits too.
        // FX3: waiting for free space or the storage rather than the unlock, it says so (EarlierWaits), not "the phone locked".
        if (BackupCategory.FILES in restoring && j.state(BackupCategory.FILES) == JournalState.COMMIT) {
            recover(ctx, files)
            if (j.state(BackupCategory.FILES) == JournalState.COMMIT) out[BackupCategory.FILES] = if (waitsForRoom) CategoryOutcome.EarlierWaits else CategoryOutcome.Locked
        }
        /** Runs [write] for [c] and notes how it ended (a throw: nothing of it changed, the journal undoes what was staged). */
        fun step(c: BackupCategory, write: () -> CategoryOutcome) {
            if (c !in restoring || out[c] != null) return
            progress(c)
            out[c] = try { write() } catch (t: Throwable) { RLog.w("restore_" + c.id, t); CategoryOutcome.Failed }
        }

        // People first in memory: the other categories point at where each person landed.
        val peopleStore = PeopleFiles.store(ctx)
        val people = if (BackupCategory.PEOPLE in restoring) RestoreMerge.people(peopleStore.list(), v.people!!.people, mode(BackupCategory.PEOPLE), mine, now) else null
        val peopleLanded: (String) -> String? = people?.let { p -> { id: String -> p.landed[id] } } ?: RestoreRefs.same(peopleStore.list().map { it.id }.toSet())
        // Settings in memory too: which helpers exist after it (keys), its History switch (decided by the plan).
        // updates.json is the updater's: staged as it is, merged by the main app (sys/RestoreApply), never read here.
        val settings = if (BackupCategory.SETTINGS in restoring) RestoreMerge.settings(settingsNow(), v.settings - BackupCategory.UPDATES_FILE, mine, peopleLanded) else null
        val robot: RobotSettings = settings?.settings ?: SettingsStore.load(ctx)
        var kept = settings?.kept?.size ?: 0

        step(BackupCategory.PEOPLE) {
            j.begin(BackupCategory.PEOPLE, RestoreProcess.VOICE)
            peopleStore.edit { f -> PeopleFile(people!!.people, f.ignored) } ?: throw IOException("people")
            j.done(BackupCategory.PEOPLE)
            CategoryOutcome.Done
        }

        // Memory, encrypted again with this phone's key (§7.7); its codes join as private items, only from your own backup.
        // History's links to memory: where each item landed, or (Memory not restored) only the items this phone has (§7.9).
        var memoryLanded: (String) -> String? = RestoreRefs.same(BackupSources.memory(ctx)?.items?.map { it.id }?.toSet().orEmpty())
        val codes = if (BackupCategory.KEYS in restoring && mine) v.memoryCodes?.items.orEmpty() else emptyList()
        val memoryToo = BackupCategory.MEMORY in restoring
        if (memoryToo || codes.isNotEmpty()) {
            val c = if (memoryToo) BackupCategory.MEMORY else BackupCategory.KEYS
            if (memoryToo) progress(c)
            val r = try {
                j.begin(BackupCategory.MEMORY, RestoreProcess.VOICE)
                val items = if (memoryToo) v.memory?.items.orEmpty() else emptyList()
                var landed: Map<String, String> = emptyMap()
                val load = MemoryFiles.vault(ctx).update { have ->
                    val m = if (memoryToo && mode(BackupCategory.MEMORY) == RestoreMode.REPLACE) MemoryRestore.replace(have, items, codes, mine, peopleLanded)
                    else MemoryRestore.add(have, items, codes, mine, now, peopleLanded)
                    landed = m.landed
                    m.file
                }
                j.done(BackupCategory.MEMORY)
                when (load) {
                    is MemoryLoad.Loaded, MemoryLoad.Empty -> { memoryLanded = { id -> landed[id] }; CategoryOutcome.Done }
                    MemoryLoad.Locked -> CategoryOutcome.Locked
                    else -> CategoryOutcome.Failed
                }
            } catch (t: Throwable) { RLog.w("restore_memory", t); CategoryOutcome.Failed }
            if (memoryToo) out[BackupCategory.MEMORY] = r
            else if (r != CategoryOutcome.Done) out[BackupCategory.KEYS] = r
        }

        // Headset, car and places: staged, then written under the store's lock (repeatable after a crash).
        val hf = HandsFreeFiles.store(ctx)
        var devicesLanded: (String) -> String? = RestoreRefs.same(hf.devices().devices.map { it.id }.toSet())
        step(BackupCategory.HANDSFREE) {
            val m = RestoreMerge.handsfree(hf.devices(), hf.headset(), hf.car(), hf.places(), v.handsfree, mode(BackupCategory.HANDSFREE), mine)
            kept += m.kept.size
            j.begin(BackupCategory.HANDSFREE, RestoreProcess.VOICE)
            val st = j.staging(BackupCategory.HANDSFREE).apply { mkdirs() }
            writeAtomic(File(st, HandsFreeStore.DEVICES), m.devices.encode())
            writeAtomic(File(st, HandsFreeStore.HEADSET), m.headset.encode())
            writeAtomic(File(st, HandsFreeStore.CAR), m.car.encode())
            writeAtomic(File(st, HandsFreeStore.PLACES), m.places.encode())
            j.commit(BackupCategory.HANDSFREE, RestoreProcess.VOICE)
            finishVoice(ctx, BackupCategory.HANDSFREE, st)
            j.done(BackupCategory.HANDSFREE)
            devicesLanded = { id -> m.landed[id] }
            CategoryOutcome.Done
        }

        step(BackupCategory.ROUTINES) {
            val rs = RoutineFiles.store(ctx)
            val file = (RoutineCodec.decode(v.routines["routines.json"]) as? RoutineFile.Ok)?.routines.orEmpty() // l10n: not text (a file name)
            val m = RestoreMerge.routines(rs.routines(), rs.settings(), rs.reminders(), file,
                v.routines["routine_settings.json"]?.let(RoutineSettingsCodec::decode), // l10n: not text (a file name)
                v.routines["reminders.json"]?.let(BriefReminders::decode).orEmpty(), // l10n: not text (a file name)
                mode(BackupCategory.ROUTINES), mine, devicesLanded, langs = AppLanguage.langs(ctx))
            kept += m.kept.size
            j.begin(BackupCategory.ROUTINES, RestoreProcess.VOICE)
            val st = j.staging(BackupCategory.ROUTINES).apply { mkdirs() }
            writeAtomic(File(st, "routines.json"), RoutineCodec.encode(m.routines)) // l10n: not text (a file name)
            writeAtomic(File(st, "routine_settings.json"), RoutineSettingsCodec.encode(m.settings)) // l10n: not text (a file name)
            writeAtomic(File(st, "reminders.json"), BriefReminders.encode(m.reminders)) // l10n: not text (a file name)
            j.commit(BackupCategory.ROUTINES, RestoreProcess.VOICE)
            finishVoice(ctx, BackupCategory.ROUTINES, st)
            j.done(BackupCategory.ROUTINES)
            CategoryOutcome.Done
        }

        // 1.1, Skills (plan §4): after routines; someone else's arrive off; :voice reads them again (Cmd.SKILLS).
        step(BackupCategory.SKILLS) {
            val ss = SkillFiles.store(ctx)
            val m = RestoreMerge.skills(ss.skills(), v.skills.orEmpty(), mode(BackupCategory.SKILLS), mine, newId = { SkillIds.make() },
                langs = AppLanguage.langs(ctx), haveSettings = ss.settings(), fileSettings = v.skillSettings)
            kept += m.kept.size
            j.begin(BackupCategory.SKILLS, RestoreProcess.VOICE)
            val st = j.staging(BackupCategory.SKILLS).apply { mkdirs() }
            writeAtomic(File(st, SkillStore.SKILLS), SkillCodec.encode(m.skills))
            m.settings?.let { writeAtomic(File(st, SkillStore.SETTINGS), app.heyrobot.core.skills.SkillSettingsCodec.encode(it)) }
            j.commit(BackupCategory.SKILLS, RestoreProcess.VOICE)
            finishVoice(ctx, BackupCategory.SKILLS, st)
            j.done(BackupCategory.SKILLS)
            CategoryOutcome.Done
        }

        // Phone lock: staged for the main app, which writes locks.json and tells :lock (§12 of the phone lock design).
        step(BackupCategory.PHONE_LOCK) {
            val f = RestoreSafety.phoneLock(LockStore.read(ctx).ok, v.phoneLock!!, mine, LockStore.readState(ctx).codeSet,
                add = mode(BackupCategory.PHONE_LOCK) == RestoreMode.ADD)
            j.begin(BackupCategory.PHONE_LOCK, RestoreProcess.VOICE)
            val st = j.staging(BackupCategory.PHONE_LOCK).apply { mkdirs() }
            writeAtomic(File(st, LockStore.LOCKS), LockCodec.encode(f))
            j.commit(BackupCategory.PHONE_LOCK, RestoreProcess.MAIN)
            CategoryOutcome.Done
        }

        // Notes, History, Transcripts, keys and the voiceprint come from one more pass over the file (every chunk checked again).
        val notes = if (BackupCategory.NOTES in restoring) RestoreMerge.byUpdated(haveNotes(), v.notes, mode(BackupCategory.NOTES)) else null
        val history = if (BackupCategory.HISTORY in restoring) RestoreMerge.byUpdated(haveHistory(), v.history, mode(BackupCategory.HISTORY)) else null
        val transcripts = if (BackupCategory.TRANSCRIPTS in restoring) RestoreMerge.transcripts(haveTranscripts(), v.transcripts.keys, mode(BackupCategory.TRANSCRIPTS)) else null
        val keys = if (BackupCategory.KEYS in restoring) RestoreMerge.keys(v.keys, robot.helpers.map { it.id }.toSet(), { BackupSecrets.hasKey(ctx, it) }, mine) else null
        // 1.4, Robot Files: planned now (People landed), its files sealed again under this phone's key in the same pass.
        val filesPlan = if (BackupCategory.FILES in restoring && out[BackupCategory.FILES] == null) try {
            planFiles(v, mode(BackupCategory.FILES), mine, now, peopleLanded)
        } catch (t: Throwable) {
            RLog.w("restore_files", t)
            out[BackupCategory.FILES] = outcome(t)
            null
        } else null
        val filesStaging = j.staging(BackupCategory.FILES)
        val sniffs = HashMap<FileId, FileType>()
        val wanted = filesPlan?.add?.associateBy { it.id }.orEmpty()
        val toSeal = filesPlan?.staged.orEmpty()
        var filesFailed: Throwable? = null
        val streamed = (listOf(BackupCategory.NOTES, BackupCategory.HISTORY, BackupCategory.TRANSCRIPTS, BackupCategory.KEYS, BackupCategory.VOICEPRINT)
            .filter { it in restoring && out[it] == null } + listOfNotNull(BackupCategory.FILES.takeIf { toSeal.isNotEmpty() })).toSet()
        val keyBytes = LinkedHashMap<String, ByteArray>()
        var voiceprint: ByteArray? = null
        if (streamed.isNotEmpty()) {
            for (c in streamed) if (c != BackupCategory.VOICEPRINT) j.begin(c, RestoreProcess.VOICE, undo = undoFor(c))
            streamed.firstOrNull()?.let(progress)
            try {
                RestoreReader.read(::open, v, k, streamed) { c, name, data ->
                    when (c) {
                        BackupCategory.NOTES -> if (BackupCategory.idOf(name) in notes!!.write) copy(data, File(j.staging(c), name))
                        BackupCategory.HISTORY -> if (BackupCategory.idOf(name) in history!!.write) {
                            val conv = ConversationCodec.fromJson(String(data.readBytes(), Charsets.UTF_8))
                            // From someone else's file, each conversation carries its content mark.
                            if (conv != null) writeAtomic(File(j.staging(c).apply { mkdirs() }, name),
                                ConversationCodec.toJson(RestoreRefs.conversation(RestoreTaint.conversation(conv, mine), memoryLanded)))
                        }
                        BackupCategory.TRANSCRIPTS -> if (BackupCategory.transcriptOf(name) in transcripts!!.write) copy(data, File(j.staging(c), name))
                        BackupCategory.KEYS -> if (name in keys!!.save) keyBytes[name] = data.readBytes()
                        BackupCategory.VOICEPRINT -> voiceprint = data.readBytes()
                        // Each file sealed again with a fresh key, never on disk in the clear; a problem here is Files' alone
                        // (the backup's own problems still stop the pass).
                        BackupCategory.FILES -> {
                            val id = FilesBackup.blobId(name)
                            if (id != null && filesFailed == null && id in toSeal) try {
                                val r = files().sealRestored(filesStaging, id, data)
                                val want = wanted.getValue(id).from
                                if (r.size != want.size || r.sha256 != want.sha256) throw FileException(FileProblem.DAMAGED)
                                // FX3: by both looks intake takes (Resealed.type), not its first bytes alone.
                                sniffs[id] = r.type
                                r.head.fill(0)
                            } catch (t: Throwable) {
                                if (t is BackupException) throw t
                                filesFailed = t
                            }
                        }
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                RLog.w("restore_read", t)
                for (c in streamed) out[c] = CategoryOutcome.Failed
            }
        }
        step(BackupCategory.NOTES) { files(j, BackupCategory.NOTES, notes!!.delete.map { "$it.json" }, NotesFiles.dir(ctx)) }
        step(BackupCategory.HISTORY) { files(j, BackupCategory.HISTORY, history!!.delete.map { "$it.json" }, Paths.conversations(ctx)) }
        step(BackupCategory.TRANSCRIPTS) { files(j, BackupCategory.TRANSCRIPTS, transcripts!!.delete, transcriptsDir()) }
        step(BackupCategory.KEYS) {
            try {
                for ((name, bytes) in keyBytes) {
                    j.keySaving(name)
                    BackupSecrets.saveKey(ctx, robot, name, bytes)
                }
            } finally {
                keyBytes.values.forEach { it.fill(0) }
            }
            j.done(BackupCategory.KEYS)
            CategoryOutcome.Done
        }
        // 1.4, Robot Files: whole or not at all. Everything that can fail for good fails before COMMIT; after it,
        // FileStore.restoreIn (Replace's deletes, the index, the files moved in) is repeated until it is done.
        step(BackupCategory.FILES) {
            val f = filesPlan!!
            try {
                filesFailed?.let { throw it }
                if (BackupCategory.FILES !in streamed) j.begin(BackupCategory.FILES, RestoreProcess.VOICE, undo = undoFor(BackupCategory.FILES))
                if (toSeal.any { !File(filesStaging, it.hex + FileStore.SEALED_EXT).isFile }) throw IOException("files") // l10n: not text (a log code)
                val add = f.add.map { e -> sniffs[e.id]?.let { FilesMerge.sniffed(e, it, mine) } ?: e }
                val extra = (if (mine) v.files!!.extra - FilesBackup.RETHUMB else emptyMap()) + (FilesBackup.RETHUMB to f.rethumb.map { it.hex })
                files().sealRestoreIndex(File(filesStaging, ENTRIES), FileIndex(add, extra))
                writeAtomic(File(filesStaging, PLAN), JSONObject().put("v", 1).put("mode", mode(BackupCategory.FILES).name).put("mine", mine)
                    .put("delete", JSONArray(f.delete.map { it.hex })).toString())
                // FX1 (finding 2): the backup's own Settings › Files is staged; the finish merges it over the file as it is
                // then (RestoreSafety.filesSettingsFile), which may be after the next unlock. Counted against now for the result.
                v.filesSettings?.let { fs ->
                    kept += RestoreSafety.filesSettings(FilesSettingsStore.current(ctx), fs.read, mine).kept.size
                    writeAtomic(File(filesStaging, FilesBackup.SETTINGS), FilesSettingsCodec.encode(fs.read, fs.extra))
                }
            } catch (t: Throwable) {
                RLog.w("restore_files", t)
                files().discardRestore(filesStaging)
                return@step outcome(t)
            }
            j.commit(BackupCategory.FILES, RestoreProcess.VOICE)
            // Committed: if the phone locked just now, it is finished after the next unlock (FilingWork, :voice's start), and
            // the result says so (FX1, finding 11); given up for good (its key gone), nothing of yours changed: not restored.
            try {
                val went = finishFiles(ctx, filesStaging, files())
                j.done(BackupCategory.FILES)
                if (went) CategoryOutcome.Done else CategoryOutcome.Failed
            } catch (x: NotNow) {
                // FX3: free space or the storage, not the unlock: its own words.
                if (x.room) CategoryOutcome.WaitsForRoom else CategoryOutcome.AfterUnlock
            }
        }

        var voiceprintSaved = false
        step(BackupCategory.VOICEPRINT) {
            val b = voiceprint ?: return@step CategoryOutcome.Failed
            try { voiceprintSaved = BackupSecrets.saveVoiceprint(ctx, b) } finally { b.fill(0) }
            if (voiceprintSaved) CategoryOutcome.Done else CategoryOutcome.Skipped(PlanSkip.VOICEPRINT_HERE)
        }

        // Settings last, staged for the main app (its one writer), with "your voiceprint is enrolled here" if it came back.
        step(BackupCategory.SETTINGS) {
            val files = LinkedHashMap(settings!!.files)
            if (voiceprintSaved) files["settings.json"] = RestoreRefs.voiceprint(settings.settings, true).toJson().toString() // l10n: not text (a file name)
            j.begin(BackupCategory.SETTINGS, RestoreProcess.VOICE)
            val st = j.staging(BackupCategory.SETTINGS).apply { mkdirs() }
            for ((name, text) in files) if (name in BackupCategory.SETTINGS_FILES) writeAtomic(File(st, name), text)
            if (voiceprintSaved) writeAtomic(File(st, BackupCategory.VOICEPRINT_STAGED), "1")
            v.settings[BackupCategory.UPDATES_FILE]?.let { writeAtomic(File(st, BackupCategory.UPDATES_STAGED), it) }
            if (mine) writeAtomic(File(st, BackupCategory.MINE_STAGED), "1")
            j.commit(BackupCategory.SETTINGS, RestoreProcess.MAIN)
            CategoryOutcome.Done
        }
        // The voiceprint came back without Settings: the main app still notes that it is enrolled here (a line of its own).
        if (voiceprintSaved && out[BackupCategory.SETTINGS] != CategoryOutcome.Done) try {
            j.begin(BackupCategory.SETTINGS, RestoreProcess.VOICE)
            writeAtomic(File(j.staging(BackupCategory.SETTINGS).apply { mkdirs() }, BackupCategory.VOICEPRINT_STAGED), "1")
            j.commit(BackupCategory.SETTINGS, RestoreProcess.MAIN)
        } catch (t: Throwable) { RLog.w("restore_voiceprint_flag", t) }
        // Anything this process left half done is undone (its staging deleted, a cut run's keys deleted again).
        finished = null
        recover(ctx, files)
        // FX3: that recovery may have finished Robot Files' commit, which waited moments ago (unlocked since, room freed): the
        // line says how it ended, not "finishes after you unlock" (the result is written below, after settled() looked).
        val waited = out[BackupCategory.FILES]
        if ((waited == CategoryOutcome.AfterUnlock || waited == CategoryOutcome.WaitsForRoom) && j.state(BackupCategory.FILES) != JournalState.COMMIT)
            finished?.let { went -> out[BackupCategory.FILES] = if (went) CategoryOutcome.Done else CategoryOutcome.Failed }
        return finish(out, v, mine, kept)
    }

    /**
     * 1.4: Robot Files' plan on this phone. Its key gone, the files here can never be read again: the preview said how many
     * go, and a new cabinet starts (Delete them all's own step) before anything is sealed.
     */
    private fun planFiles(v: VerifiedBackup, mode: RestoreMode, mine: Boolean, now: Long, people: (String) -> String?): FilesRestored {
        val vault = files()
        if (vault.load() == IndexLoad.KeyGone && !vault.deleteAll()) throw FileException(FileProblem.LOCKED)
        val have = vault.prepareRestore()
        return RestoreMerge.files(have, v.files ?: FileIndex(), mode, mine, now, people)
    }

    /** FX1: what [RestoreJournal.begin] does with [c]'s leftover staging first: Robot Files' sealed copies have their keys destroyed. */
    private fun undoFor(c: BackupCategory): (File) -> Unit = if (c == BackupCategory.FILES) { st -> files().discardRestore(st) } else { _ -> }

    /** How a category ended when [t] stopped it: the phone locked ("Unlock and restore again"), or failed; nothing of it changed. */
    private fun outcome(t: Throwable): CategoryOutcome =
        if ((t as? FileException)?.problem == FileProblem.LOCKED || Assistant.locked(ctx)) CategoryOutcome.Locked else CategoryOutcome.Failed

    /** A many-files category: Replace's deletions staged, COMMIT, moved in, DONE. */
    private fun files(j: app.heyrobot.core.backup.RestoreJournal, c: BackupCategory, delete: Collection<String>, live: File): CategoryOutcome {
        val st = j.staging(c)
        if (delete.isNotEmpty()) StagedFiles.stageDelete(st, delete)
        j.commit(c, RestoreProcess.VOICE)
        finishVoice(ctx, c, st, live)
        j.done(c)
        return CategoryOutcome.Done
    }

    private fun copy(data: InputStream, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".tmp")
        tmp.outputStream().use { data.copyTo(it, BackupFormat.CHUNK) }
        if (!tmp.renameTo(to)) throw IOException("rename")
    }

    private fun finish(out: Map<BackupCategory, CategoryOutcome>, v: VerifiedBackup, mine: Boolean, kept: Int): RestoreResult {
        val r = RestoreResult(System.currentTimeMillis(), mine, out, kept, v.manifest.robot)
        try { writeAtomic(RestoreFiles.resultFile(ctx), r.toJson()) } catch (t: Throwable) { RLog.w("restore_result", t) }
        val notDone = out.filterValues { it != CategoryOutcome.Done && it !is CategoryOutcome.Skipped }.keys
        VoiceLog.add(VoiceEvent.RESTORE, if (notDone.isEmpty()) 1 else 2, BackupExport.bits(r.done), BackupExport.bits(notDone))
        return r
    }

    override fun close() {
        key?.close()
        key = null
        incoming.delete()
    }

    companion object {
        /** A transcription's heartbeat is fresh for this long. */
        const val HEARTBEAT_MS = 2 * 60_000L
        /** 1.4: Robot Files' staging: the entries the restore adds (sealed) and its plan (ids and switches only). */
        const val ENTRIES = "entries.rf"
        const val PLAN = "plan.json"

        /** FX3: why Robot Files' commit last stayed at COMMIT: free space or the storage (true), or the unlock (false). */
        @Volatile private var waitsForRoom = false
        /** FX3: how the last finish of Robot Files' commit ended (true: its files went in), for a restore's trailing recovery. */
        @Volatile private var finished: Boolean? = null

        /** 1.4: a restore is being written in this process now (FilingWork's unlock recovery leaves the journal alone then). */
        @Volatile var running = false
            private set
        /** Room left free after a restore (EST). */
        const val SPARE = 512L * 1024 * 1024

        /** [c]'s staged files written where they live (repeatable: after a crash, RestoreJournal.recover calls it again). */
        fun finishVoice(ctx: Context, c: BackupCategory, staging: File, live: File? = null, files: () -> FilesVault = { RobotFiles.vault(ctx) }) {
            when (c) {
                BackupCategory.FILES -> finishFiles(ctx, staging, files()).let { went ->
                    finished = went
                    settled(ctx, went)
                }
                BackupCategory.HANDSFREE -> {
                    val hf = HandsFreeFiles.store(ctx)
                    readOrNull(File(staging, HandsFreeStore.DEVICES))?.let { t -> (DeviceMemory.decode(t) as? DeviceFile.Ok)?.memory }?.let { m -> hf.editDevices { m } }
                    readOrNull(File(staging, HandsFreeStore.HEADSET))?.let { t -> HeadsetFile.decode(t) }?.let { h -> hf.editHeadset { h } }
                    readOrNull(File(staging, HandsFreeStore.CAR))?.let { t -> CarFile.decode(t) }?.let { x -> hf.editCar { x } }
                    readOrNull(File(staging, HandsFreeStore.PLACES))?.let { t -> Places.decode(t) }?.let { p -> hf.editPlaces { p } }
                }
                BackupCategory.ROUTINES -> {
                    val rs = RoutineFiles.store(ctx)
                    readOrNull(File(staging, "routines.json"))?.let { t -> (RoutineCodec.decode(t) as? RoutineFile.Ok)?.routines }?.let { l -> rs.edit { l } } // l10n: not text (a file name)
                    readOrNull(File(staging, "routine_settings.json"))?.let(RoutineSettingsCodec::decode)?.let { s -> rs.editSettings { s } } // l10n: not text (a file name)
                    readOrNull(File(staging, "reminders.json"))?.let(BriefReminders::decode)?.let { l -> rs.editReminders { l } } // l10n: not text (a file name)
                }
                BackupCategory.SKILLS -> {
                    val ss = SkillFiles.store(ctx)
                    readOrNull(File(staging, SkillStore.SKILLS))?.let { t -> (SkillCodec.decode(t) as? SkillFile.Ok)?.skills }?.let { l -> ss.edit { l } }
                    readOrNull(File(staging, SkillStore.SETTINGS))?.let { t -> (app.heyrobot.core.skills.SkillSettingsCodec.decode(t) as? app.heyrobot.core.skills.SkillSettingsFile.Ok)?.read }
                        ?.let { ss.saveSettings(it) }
                    // Phase B: the fill samples start over after a restore (plan §4), the safe side.
                    ss.editState { app.heyrobot.core.skills.SkillStats.cleared(it) }
                    SkillFiles.changed(ctx)
                }
                BackupCategory.NOTES -> StagedFiles.moveIn(staging, live ?: NotesFiles.dir(ctx))
                BackupCategory.HISTORY -> StagedFiles.moveIn(staging, live ?: Paths.conversations(ctx))
                BackupCategory.TRANSCRIPTS -> StagedFiles.moveIn(staging, live ?: File(ctx.filesDir, TranscriptFiles.DIR))
                else -> {}
            }
        }

        /** At :voice's start (VoiceHub) and after a restore: its COMMIT categories finished, its STAGED ones undone. */
        fun recover(ctx: Context, files: () -> FilesVault = { RobotFiles.vault(ctx) }) {
            try {
                val j = RestoreFiles.journal(ctx)
                if (j.read().isEmpty()) return
                j.recover(RestoreProcess.VOICE, { c, st -> finishVoice(ctx, c, st, files = files) }, { names -> BackupSecrets.deleteKeys(ctx, names) },
                    undo = { c, st -> if (c == BackupCategory.FILES) files().discardRestore(st) })
            } catch (t: Throwable) {
                RLog.w("restore_recover", t)
            }
        }

        /**
         * 1.4: after each unlock (FilingWork): a restore's Robot Files part that the phone locking left at COMMIT is finished.
         * Never while a restore is being written in this process: its staged lines aren't leftovers.
         */
        fun afterUnlock(ctx: Context) {
            if (running) return
            try {
                if (RestoreFiles.journal(ctx).state(BackupCategory.FILES) == JournalState.COMMIT) recover(ctx)
            } catch (t: Throwable) {
                RLog.w("restore_unlock", t)
            }
        }

        /**
         * 1.4: Robot Files' commit, repeatable (FileStore.restoreIn: Replace's deletes, the index, the files moved in), then
         * Settings › Files written (as Skills' restore writes skill_settings.json from :voice; the restart re-reads it).
         * The key needed and the phone locked: [NotNow], and the next unlock finishes it. A commit whose sealed entries can't
         * be read for good (their key gone) is given up: its staged files destroyed, nothing of yours changed beyond them.
         * FX1 (files-1.4-review.md finding 2): Settings › Files only once the files went in, and merged over the file as it
         * is now, under its lock (FilesSettingsStore.restore): travel mode turned on, or a drawer hidden, since staging stays.
         * True when Robot Files' part went in.
         */
        private fun finishFiles(ctx: Context, staging: File, vault: FilesVault): Boolean {
            val plan = try { JSONObject(readOrNull(File(staging, PLAN)) ?: "") } catch (_: Exception) { null }
            var went = false
            if (plan != null) {
                val entries = File(staging, ENTRIES)
                val mine = plan.optBoolean("mine", false)
                if (entries.isFile) try {
                    val st = vault.readRestoreIndex(entries)
                    val mode = RestoreMode.entries.firstOrNull { it.name == plan.optString("mode") } ?: RestoreMode.ADD
                    val delete = plan.optJSONArray("delete")?.let { a -> (0 until a.length()).mapNotNull { FileId.of(a.optString(it)) } }.orEmpty().toSet()
                    val rethumb = FilesBackup.ids(st.extra[FilesBackup.RETHUMB])
                    try {
                        vault.restoreIn(staging, st.entries, delete) { here -> FilesMerge.extra(here, st.extra - FilesBackup.RETHUMB, mode, mine, rethumb) }
                    } catch (x: IOException) {
                        // FX3: the commit's write failing (the index, a file moved in) is repeatable: not now, for the storage.
                        // Only here: a damaged entries.rf reads as DAMAGED (FileSeal), never as waiting for room.
                        RLog.w("restore_files_finish", x)
                        waitsForRoom = true
                        throw NotNow(room = true)
                    }
                    went = true
                } catch (x: FileException) {
                    if (x.problem == FileProblem.LOCKED || x.problem == FileProblem.IO || x.problem == FileProblem.LOW_SPACE) {
                        waitsForRoom = x.problem != FileProblem.LOCKED
                        throw NotNow(room = waitsForRoom)
                    }
                    RLog.w("restore_files_finish", x)
                }
                if (went) FilesSettingsStore.restore(ctx, readOrNull(File(staging, FilesBackup.SETTINGS)), mine)
            }
            vault.discardRestore(staging)
            vault.drop()
            return went
        }

        /** FX1 (finding 11): the last restore's Files line said "finishes after you unlock" (FX3: or waits for room): now restored, or not. */
        private fun settled(ctx: Context, went: Boolean) {
            try {
                val f = RestoreFiles.resultFile(ctx)
                val r = RestoreResult.fromJson(readOrNull(f)) ?: return
                val o = r.outcomes[BackupCategory.FILES]
                if (o != CategoryOutcome.AfterUnlock && o != CategoryOutcome.WaitsForRoom) return
                writeAtomic(f, r.copy(outcomes = r.outcomes + (BackupCategory.FILES to if (went) CategoryOutcome.Done else CategoryOutcome.Failed)).toJson())
            } catch (t: Throwable) {
                RLog.w("restore_result_files", t)
            }
        }
    }
}
