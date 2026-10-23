// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.TimeWindow
import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefReminder
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.DeviceKind
import app.heyrobot.core.LookSettings
import app.heyrobot.core.Note
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.SessionStatus
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolName
import app.heyrobot.core.TranscriptCodec
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.TurnRole
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceFamily
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.WebAsk
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileLevel
import app.heyrobot.core.files.FileRemind
import app.heyrobot.core.files.FileReminders
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FiledBy
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.FilesSettingsCodec
import app.heyrobot.core.files.Filing
import app.heyrobot.core.files.FilingStep
import app.heyrobot.core.files.Provenance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Tests' helpers: backups made with a raw key (no Argon2id), and the fixtures' content. */
object BackupKit {
    val SALT = ByteArray(BackupFormat.SALT) { (it * 11 + 3).toByte() }
    const val CODE = 1_010_099L
    /** A marker that must never reach a log line, an exception's message or a toString (BackupLogTest). */
    const val MARK = "zq9marker"

    fun key(seed: Int = 1) = BackupKey(ByteArray(BackupKdf.KEY_BYTES) { (it * 7 + seed).toByte() })

    fun header(salt: ByteArray = SALT, kdf: KdfParams = KdfParams.CURRENT) = BackupHeader(BackupFormat.FORMAT, kdf, salt, CODE)

    /** [plain] encrypted under [header] and [key]: the whole file. */
    fun encrypt(plain: ByteArray, header: BackupHeader = header(), key: BackupKey = key()): ByteArray {
        val out = ByteArrayOutputStream()
        BackupWriter(out, header, key).use { it.write(plain) }
        return out.toByteArray()
    }

    /** The plaintext of a whole [file] (header included). */
    fun decrypt(file: ByteArray, key: BackupKey = key()): ByteArray {
        val h = BackupHeader.parse(file)
        return BackupReader(ByteArrayInputStream(file, BackupFormat.HEADER_SIZE, file.size), h, key).readBytes()
    }

    fun manifest(vararg categories: BackupCategory, keys: Boolean = false) =
        BackupManifest("1.1.0", CODE, 1_760_270_000_000L, categories.associate { it.id to CategoryCount(0, 0) }, keys)

    /** The records [build] writes, as plaintext (manifest first, end last). */
    fun records(manifest: BackupManifest = manifest(*BackupCategory.entries.toTypedArray()), build: BackupContainerWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        BackupContainerWriter(out).apply {
            manifest(manifest)
            build()
            end()
        }
        return out.toByteArray()
    }

    fun file(header: BackupHeader = header(), key: BackupKey = key(), build: BackupContainerWriter.() -> Unit): ByteArray =
        encrypt(records(build = build), header, key)

    fun opener(bytes: ByteArray): () -> java.io.InputStream = { ByteArrayInputStream(bytes) }

    fun verify(bytes: ByteArray, key: BackupKey = key()): VerifiedBackup = RestoreReader.verify(opener(bytes), BackupHeader.parse(bytes), key)

    fun problem(block: () -> Unit): OpenProblem? = try {
        block()
        null
    } catch (x: BackupException) {
        x.problem
    }

    fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------------------------------- the fixtures' content

    const val T0 = 1_760_270_000_000L
    const val MARIE = "6f0c2a3e-0000-4000-8000-000000000001"
    const val BOB = "6f0c2a3e-0000-4000-8000-000000000002"
    const val MEM_MARIE = "m_0mgnhw6o0aaaaaa"
    const val MEM_PLAIN = "m_0mgnhw6o0bbbbbb"
    const val MEM_CODE = "m_0mgnhw6o0cccccc"
    const val NOTE_1 = "note-0001"
    const val NOTE_2 = "note-0002"
    const val CONV = "conv-0001"
    const val TRANSCRIPT = "20261011-1402-7f3a"
    const val ROUTINE_TIME = "routine-time-0001"
    const val ROUTINE_HEADSET = "routine-head-0001"
    const val JARVIS = "jarvis"

    fun people(): List<Person> = listOf(
        Person(MARIE, "Marie Tremblay", phones = listOf(ContactPhone("+15145550101", PhoneKind.MOBILE, "Mobile")), updatedAt = T0),
        Person(BOB, "Bob $MARK", emails = listOf("bob@example.org"), updatedAt = T0),
    )

    fun memory(): MemoryFile = MemoryFile(items = listOf(
        MemoryItem(MEM_MARIE, "Marie likes jasmine tea", about = listOf(MARIE), createdAt = T0),
        MemoryItem(MEM_PLAIN, "My bike is a blue $MARK", source = MemorySource.SAID, createdAt = T0),
    ))

    fun memoryCodes(): MemoryFile = MemoryFile(items = listOf(MemoryItem(MEM_CODE, "My bank PIN is 4321", secret = true, createdAt = T0)))

    fun notes(): List<Note> = listOf(
        Note(NOTE_1, "Groceries", "milk, eggs, $MARK", T0, T0 + 1_000),
        Note(NOTE_2, "", "Call the plumber", T0, T0 + 2_000),
    )

    fun conversation(): StoredConversation = StoredConversation(CONV, T0, T0 + 5_000, listOf(
        ChatTurn("t1", TurnRole.USER, "What tea does Marie like?", T0, memoryIds = listOf(MEM_MARIE)),
        ChatTurn("t2", TurnRole.ASSISTANT, "Jasmine tea.", T0 + 1_000),
    ))

    fun transcriptMeta() = TranscriptMeta(TRANSCRIPT, "Team meeting", T0, T0 + 60_000, SessionStatus.ENDED)

    fun transcriptLines(): String =
        TranscriptCodec.encode(TranscriptEvent.Line(0, 0.0, 2.5, "S1", "en", "Hello everyone $MARK")) + "\n"

    fun settings(): RobotSettings = RobotSettings(
        approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.SEND_TEXT to Approval.AUTO),
        personalToHelpers = true,
        eventCalendar = 42,
        meVoiceprint = true,
        confirmedTerms = mapOf("gemma" to T0),
        helpers = listOf(HelperConfig.claude(), HelperConfig.of(Presets.OTHER, JARVIS, "af_heart", "Jarvis", "https://jarvis.example").copy(trust = true)),
    )

    fun messages() = MessagesFile(
        settings = MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM)), voiceYesSends = true),
        noCard = setOf(MARIE),
        pins = mapOf("com.cheogram.android" to AppPin(setOf("AA".repeat(32)), "org.fdroid.fdroid")),
    )

    fun termux() = TermuxSettings(enabled = true, scripts = mapOf("backup.sh" to ScriptLevel.AUTO), trustedSigner = "BB".repeat(32),
        trustedVersionCode = 1022, trustedUpdatedAt = T0, trustedVersion = "0.119.0")

    fun routines(): List<Routine> = listOf(
        Routine(ROUTINE_TIME, "Good morning", RoutineTrigger.At(LocalTime.of(7, 0), DayOfWeek.entries.toSet()), listOf(RoutineStep.Say("Good morning")), created = T0),
        Routine(ROUTINE_HEADSET, "Headset on", RoutineTrigger.Device(DeviceKind.HEADSET, "dev-1"), listOf(RoutineStep.Reminders), created = T0),
    )

    fun devices() = DeviceMemory(listOf(
        RememberedDevice(1, DeviceRole.HEADSET, DeviceFamily.BLUETOOTH, "Pixel Buds", tail = "EE:FF", address = "AA:BB:CC:DD:EE:FF", private = true, added = T0),
    ), 2)

    /** The fixture's files, by category and name, as Robot's stores write them. */
    fun content(): Map<BackupCategory, Map<String, ByteArray>> = linkedMapOf(
        BackupCategory.SETTINGS to linkedMapOf(
            "settings.json" to text(settings().toJson().toString()),
            LookSettings.FILE to text(LookSettings(wallpaperColors = false).toJson()),
            MemorySettings.FILE to text(MemorySettings(onLockScreen = true).toJson()),
            MessagesFile.FILE to text(messages().toJson()),
            "web.json" to text(WebSettings(ask = WebAsk.NEVER).toJson().toString()),
            "termux.json" to text(termux().toJson().toString()),
            UpdateSettings.FILE to text(UpdateSettings(testBuilds = true).toJson()),
            FeedbackPrefs.FILE to text(FeedbackPrefs("+15145550199").toJson()),
        ),
        BackupCategory.PEOPLE to mapOf(BackupCategory.PEOPLE_FILE to text(PeopleCodec.toJson(people()))),
        BackupCategory.MEMORY to mapOf(BackupCategory.MEMORY_FILE to text(MemoryCodec.toJson(memory()))),
        BackupCategory.HANDSFREE to linkedMapOf(
            "devices.json" to text(devices().encode()),
            "headset.json" to text(HeadsetFile(safety = HandsFreeSafety(personalOnlyPrivate = false)).encode()),
            "car.json" to text(CarFile(on = true).encode()),
            "places.json" to text(Places(home = SavedPlace.of("Home", 45.5, -73.6)).encode()),
        ),
        BackupCategory.ROUTINES to linkedMapOf(
            "routines.json" to text(RoutineCodec.encode(routines())),
            "routine_settings.json" to text(RoutineSettingsCodec.encode(RoutineSettings(briefLock = BriefLock.EVERYTHING))),
            "reminders.json" to text(BriefReminders.encode(listOf(BriefReminder("Bring the passport", LocalDate.of(2026, 10, 13))))),
        ),
        // 1.1: Skills' skills.json, one skill.
        BackupCategory.SKILLS to mapOf("skills.json" to text(app.heyrobot.core.skills.SkillCodec.encode(listOf(app.heyrobot.core.skills.SkillFixtures.headingHome())))),
        // 1.1: Phone lock's locks.json, one lock for yourself, on.
        BackupCategory.PHONE_LOCK to mapOf(BackupCategory.LOCK_FILE to text(app.heyrobot.core.lock.LockCodec.encode(app.heyrobot.core.lock.LockFile(
            app.heyrobot.core.lock.LockSettings(on = true), listOf(app.heyrobot.core.lock.PhoneLock("lk_bed0", "Bedtime",
                app.heyrobot.core.lock.LockRule.Time(TimeWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(7, 0))))))))),
        BackupCategory.NOTES to notes().associate { "${it.id}.json" to text(NoteCodec.toJson(it)) },
        BackupCategory.HISTORY to mapOf("$CONV.json" to text(ConversationCodec.toJson(conversation()))),
        BackupCategory.TRANSCRIPTS to linkedMapOf(
            "$TRANSCRIPT/meta.json" to text(transcriptMeta().toJson()),
            "$TRANSCRIPT/lines.jsonl" to text(transcriptLines()),
            "$TRANSCRIPT/summary-1.json" to text("{\"v\":1,\"text\":\"A short meeting.\"}"),
        ),
        BackupCategory.KEYS to linkedMapOf(
            "helper/claude" to text("test-key-$MARK-claude"),
            "search/brave" to text("test-key-$MARK-brave"),
            BackupCategory.MEMORY_CODES to text(MemoryCodec.toJson(memoryCodes())),
        ),
        // 1.4, Robot Files: two files' bytes, then the catalogue naming them, then Settings › Files.
        BackupCategory.FILES to linkedMapOf(
            FilesBackup.blobName(FileId.of(FILE_RECEIPT)!!) to receiptBytes(),
            FilesBackup.blobName(FileId.of(FILE_PHOTO)!!) to photoBytes(),
            FilesBackup.INDEX to FileIndexCodec.encode(filesIndex()),
            FilesBackup.SETTINGS to text(FilesSettingsCodec.encode(FilesSettings(bills = FileLevel.ASK, reminders = FileReminders.OFF))),
        ),
    )

    // 1.4, Robot Files' content (demo data only).
    const val FILE_RECEIPT = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    const val FILE_PHOTO = "00112233445566778899aabbccddeeff"

    /** A receipt PDF's bytes, a little over one 64 KiB chunk, with the marker in them. */
    fun receiptBytes(): ByteArray = "%PDF-1.7\n% Costco $MARK\n".toByteArray() + ByteArray(70_000) { (it % 251).toByte() }

    /** A photo's bytes (a JPEG's first bytes). */
    fun photoBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3_000) { (it % 13).toByte() }

    fun sha256(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The catalogue: a receipt Robot filed (your tag, a person, a brief reminder, a field you set) and a photo; your drawer Car. */
    fun filesIndex(): FileIndex = FileIndex(listOf(
        FileEntry(FileId.of(FILE_RECEIPT)!!, "2026-08-14 Costco 84.12 CAD $MARK", FileKind.RECEIPT, FileState.FILED,
            Provenance(FileSource.SHARE, T0, receiptBytes().size.toLong(), sha256(receiptBytes()), app = "org.example.mail", type = "application/pdf",
                filedBy = FiledBy.ROBOT, filedAt = T0 + 1_000),
            named = true, drawer = Drawers.RECEIPTS, tags = listOf("2026-08", "business"), people = listOf(MARIE),
            fields = linkedMapOf("merchant" to FileField("Costco $MARK", FieldBy.READ), "total" to FileField("84.12", FieldBy.YOU),
                "currency" to FileField("CAD", FieldBy.READ), FileRemind.FIELD to FileField("due:2026-10-15", FieldBy.YOU)),
            text = "COSTCO WHOLESALE $MARK TOTAL 84.12", thumb = true, filing = Filing(FilingStep.FILED, wasName = "receipt.pdf")),
        FileEntry(FileId.of(FILE_PHOTO)!!, "Beach", FileKind.PHOTO, FileState.FILED,
            Provenance(FileSource.PANEL, T0 + 2_000, photoBytes().size.toLong(), sha256(photoBytes()), type = "image/jpeg", filedBy = FiledBy.YOU, filedAt = T0 + 3_000),
            drawer = "Car", thumb = true),
    ), mapOf(FileViews.DRAWERS_KEY to listOf("Car")))

    /** [content]'s records, in its order, with the manifest listing what each category holds. */
    fun contentRecords(content: Map<BackupCategory, Map<String, ByteArray>> = content(), robot: String = "1.1.0"): ByteArray {
        val m = BackupManifest(robot, CODE, T0, content.entries.associate { (c, f) -> c.id to CategoryCount(f.size.toLong(), f.values.sumOf { it.size.toLong() }) },
            keys = BackupCategory.KEYS in content)
        return records(m) { for ((c, files) in content) for ((n, b) in files) entry(c, n, b) }
    }

    fun resource(name: String): ByteArray? = BackupKit::class.java.getResourceAsStream("/backup/$name")?.use { it.readBytes() }

    /** Where the committed fixtures live (core's tests run with the module as their directory). */
    fun fixtureDir(): File = File("src/test/resources/backup")
}

/** A routine step for tests that need a tool step. */
internal fun timerStep() = RoutineStep.Tool(Action.SetTimer(60))

/** An assistant turn that read a note (content). */
internal fun readNoteTurn(id: String, at: Long) = ChatTurn(id, TurnRole.ASSISTANT, "Your note says hi.", at,
    actions = listOf(ActionRecord(ToolName.FIND_NOTES, "Found a note", ActionStatus.DONE, content = true)))
