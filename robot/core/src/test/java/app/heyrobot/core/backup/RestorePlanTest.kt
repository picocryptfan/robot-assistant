// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.BriefReminder
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLimits
import app.heyrobot.core.memory.MemoryRestore
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceFamily
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.car.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * Design §7.3: every category on a fresh phone, in Add mode and in Replace mode; a second restore in Add mode adds
 * nothing; History off and Memory off skip their categories; caps refuse a category.
 */
class RestorePlanTest {
    private val v by lazy { BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords())) }

    @Test fun thePlanOnAFreshPhoneAndOnOneWithData() {
        val fresh = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = false)
        // The fixture has no voiceprint (1.1's optional category).
        assertEquals(BackupCategory.entries - BackupCategory.SENSITIVE, fresh.restoring)
        assertEquals(PlanSkip.NOT_IN_BACKUP, fresh.of(BackupCategory.VOICEPRINT).skip)
        assertEquals(PlanSkip.NOT_CHOSEN, fresh.of(BackupCategory.KEYS).skip)
        assertTrue(fresh.categories.filter { it.category != BackupCategory.KEYS }.all { it.mode == RestoreMode.FRESH })
        assertEquals(2L, fresh.of(BackupCategory.NOTES).inBackup)

        val has = PhoneHas(BackupCategory.entries.associateWith { 3 })
        val add = RestorePlanner.plan(v, has, RestorePlanner.defaults(v), mine = false)
        assertEquals(RestoreMode.REPLACE, add.of(BackupCategory.SETTINGS).mode)
        for (c in BackupCategory.entries - BackupCategory.SETTINGS - BackupCategory.VOICEPRINT) assertEquals(c.id, RestoreMode.ADD, add.of(c).mode)
        val replace = RestorePlanner.plan(v, has, BackupCategory.entries.associateWith { CategoryChoice(replace = true) }, mine = true)
        for (c in BackupCategory.entries - BackupCategory.SENSITIVE) assertEquals(c.id, RestoreMode.REPLACE, replace.of(c).mode)
        // Keys are never replaced: only added where this phone has none.
        assertEquals(RestoreMode.ADD, replace.of(BackupCategory.KEYS).mode)
        assertTrue(replace.of(BackupCategory.KEYS).restores)
        // Not chosen, or not yours.
        val some = RestorePlanner.plan(v, has, mapOf(BackupCategory.NOTES to CategoryChoice(), BackupCategory.KEYS to CategoryChoice()), mine = false)
        assertEquals(listOf(BackupCategory.NOTES), some.restoring)
        assertEquals(PlanSkip.NOT_YOURS, some.of(BackupCategory.KEYS).skip)
        assertEquals(PlanSkip.NOT_CHOSEN, some.of(BackupCategory.PEOPLE).skip)
    }

    @Test fun historyAndMemoryOff() {
        // This phone's switches when Settings isn't restored.
        val off = RestorePlanner.plan(v, PhoneHas(emptyMap(), historyOn = false, memoryOn = false),
            RestorePlanner.defaults(v) + (BackupCategory.SETTINGS to CategoryChoice(restore = false)), mine = true)
        assertEquals(PlanSkip.HISTORY_OFF, off.of(BackupCategory.HISTORY).skip)
        assertEquals(PlanSkip.MEMORY_OFF, off.of(BackupCategory.MEMORY).skip)
        // The backup's when it is: "after the Settings category".
        val on = RestorePlanner.plan(v, PhoneHas(emptyMap(), historyOn = false, memoryOn = false), RestorePlanner.defaults(v), mine = true)
        assertNull(on.of(BackupCategory.HISTORY).skip)
        assertNull(on.of(BackupCategory.MEMORY).skip)
        val noHistory = BackupKit.content().toMutableMap().apply {
            put(BackupCategory.SETTINGS, mapOf("settings.json" to BackupKit.text(RobotSettings(saveHistory = false).toJson().toString()),
                MemorySettings.FILE to BackupKit.text(MemorySettings(on = false).toJson())))
        }
        val w = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords(noHistory)))
        val p = RestorePlanner.plan(w, PhoneHas(emptyMap()), RestorePlanner.defaults(w), mine = true)
        assertEquals(PlanSkip.HISTORY_OFF, p.of(BackupCategory.HISTORY).skip)
        assertEquals(PlanSkip.MEMORY_OFF, p.of(BackupCategory.MEMORY).skip)
    }

    @Test fun capsRefuseACategory() {
        val big = RawRecords().manifest()
        repeat(9) { big.entry("settings", "x$it.json", BackupKit.text("{}")) }
        big.entry("notes", "a.json", BackupKit.text("{\"v\":1,\"id\":\"a\",\"title\":\"\",\"text\":\"x\",\"created\":1,\"updated\":2}"))
        val w = BackupKit.verify(BackupKit.encrypt(big.end().bytes()))
        val p = RestorePlanner.plan(w, PhoneHas(emptyMap()), RestorePlanner.defaults(w) + (BackupCategory.SETTINGS to CategoryChoice()), mine = true)
        assertEquals(PlanSkip.REFUSED, p.of(BackupCategory.SETTINGS).skip)
        assertNull(p.of(BackupCategory.NOTES).skip)
        val tooMany = RawRecords().manifest()
        for (n in BackupCategory.SETTINGS_FILES.keys) tooMany.entry("settings", n, BackupKit.text("{}"))
        tooMany.entry("settings", "settings.json ", BackupKit.text("{}"))
        assertFalse(BackupKit.verify(BackupKit.encrypt(tooMany.end().bytes())).has(BackupCategory.SETTINGS))
    }

    @Test fun peopleFreshAddReplaceAndTwice() {
        val here = listOf(Person("here-1", "Marie Tremblay", phones = listOf(ContactPhone("+1 514 555 0101", PhoneKind.MOBILE, "Mobile")), confirmed = false),
            Person("here-2", "Zoé", emails = listOf("zoe@example.org")))
        val file = BackupKit.people()
        val fresh = RestoreMerge.people(emptyList(), file, RestoreMode.FRESH, mine = true, now = 5)
        assertEquals(file.map { it.id }, fresh.people.map { it.id })
        val add = RestoreMerge.people(here, file, RestoreMode.ADD, mine = false, now = 5)
        // Marie is the same person (her number, her name): she lands on "here-1" and stays unconfirmed; Bob is added.
        assertEquals(3, add.people.size)
        assertEquals("here-1", add.landed[BackupKit.MARIE])
        assertEquals(BackupKit.BOB, add.landed[BackupKit.BOB])
        assertFalse(add.people.first { it.id == "here-1" }.confirmed)
        assertFalse(add.people.first { it.id == BackupKit.BOB }.confirmed)
        assertTrue(add.people.first { it.id == "here-2" }.confirmed)
        // A second time: nothing added, nothing changed.
        val again = RestoreMerge.people(add.people, file, RestoreMode.ADD, mine = false, now = 6)
        assertEquals(0, again.added)
        assertEquals(add.people, again.people)
        val replace = RestoreMerge.people(here, file, RestoreMode.REPLACE, mine = true, now = 5)
        assertEquals(file.map { it.id }, replace.people.map { it.id })
        // An id someone here already has, for someone else: a new id.
        val clash = RestoreMerge.people(listOf(Person(BackupKit.BOB, "Not Bob", emails = listOf("nb@example.org"))), file, RestoreMode.ADD, mine = true, now = 5,
            newId = { "new-bob" })
        assertEquals("new-bob", clash.landed[BackupKit.BOB])
        assertEquals(setOf(BackupKit.BOB, "new-bob", BackupKit.MARIE), clash.people.map { it.id }.toSet())
    }

    @Test fun memoryFreshAddReplaceAndTwice() {
        val file = BackupKit.memory().items
        val codes = BackupKit.memoryCodes().items
        val here = MemoryFile(items = listOf(MemoryItem("m_000000000here01", "Marie likes jasmine tea", createdAt = 1)))
        val add = MemoryRestore.add(here, file, codes, mine = false, now = BackupKit.T0, people = { it }, random = Random(1))
        // The same words are skipped (and land on the item here), the other gets a new id, and codes need "yours".
        assertEquals(2, add.file.items.size)
        assertEquals("m_000000000here01", add.landed[BackupKit.MEM_MARIE])
        val newId = add.landed[BackupKit.MEM_PLAIN]!!
        assertTrue(newId != BackupKit.MEM_PLAIN && newId in add.file.items.map { it.id })
        assertNull(add.landed[BackupKit.MEM_CODE])
        val again = MemoryRestore.add(add.file, file, codes, mine = false, now = BackupKit.T0, people = { it }, random = Random(2))
        assertEquals(add.file.items, again.file.items)
        assertEquals(0, again.added)
        // Yours with keys: the code joins as a private item, once.
        val mine = MemoryRestore.add(here, file, codes, mine = true, now = BackupKit.T0, people = { it }, random = Random(3))
        assertEquals(3, mine.file.items.size)
        assertTrue(mine.file.items.last().secret)
        val mineAgain = MemoryRestore.add(mine.file, file, codes, mine = true, now = BackupKit.T0, people = { it }, random = Random(4))
        assertEquals(mine.file.items, mineAgain.file.items)
        // Replace keeps the file's ids, at memory's caps.
        val replace = MemoryRestore.replace(here, file, codes, mine = true, people = { it })
        assertEquals(listOf(BackupKit.MEM_MARIE, BackupKit.MEM_PLAIN, BackupKit.MEM_CODE), replace.file.items.map { it.id })
        val many = (0 until 2_100).map { MemoryItem("m_${it.toString().padStart(9, '0')}abcdef", "item $it", pinned = true, createdAt = 1) }
        val capped = MemoryRestore.replace(MemoryFile(), many, emptyList(), mine = true, people = { it })
        assertEquals(MemoryLimits.MAX_ITEMS, capped.file.items.size)
        assertEquals(MemoryLimits.MAX_PINNED, capped.file.items.count { it.pinned })
        // Add stops at memory's 2,000 too.
        val full = MemoryRestore.add(MemoryFile(items = many.take(1_999)), file, emptyList(), mine = true, now = BackupKit.T0, people = { it })
        assertEquals(MemoryLimits.MAX_ITEMS, full.file.items.size)
        assertEquals(1, full.skipped)
    }

    @Test fun filesByIdAndByDate() {
        val here = mapOf("a" to 10L, "b" to 10L, "c" to 10L)
        val file = mapOf("a" to 5L, "b" to 20L, "d" to 1L)
        val add = RestoreMerge.byUpdated(here, file, RestoreMode.ADD)
        assertEquals(setOf("b", "d"), add.write)
        assertTrue(add.delete.isEmpty())
        // Twice: what was written is now here with the same date.
        assertTrue(RestoreMerge.byUpdated(here + mapOf("b" to 20L, "d" to 1L), file, RestoreMode.ADD).write.isEmpty())
        val replace = RestoreMerge.byUpdated(here, file, RestoreMode.REPLACE)
        assertEquals(file.keys, replace.write)
        assertEquals(setOf("c"), replace.delete)
        assertEquals(file.keys, RestoreMerge.byUpdated(emptyMap(), file, RestoreMode.FRESH).write)
        // Transcripts: one already here is left alone.
        val t = RestoreMerge.transcripts(setOf("x", "y"), setOf("y", "z"), RestoreMode.ADD)
        assertEquals(setOf("z"), t.write)
        assertTrue(RestoreMerge.transcripts(setOf("x", "y", "z"), setOf("y", "z"), RestoreMode.ADD).write.isEmpty())
        val tr = RestoreMerge.transcripts(setOf("x", "y"), setOf("y", "z"), RestoreMode.REPLACE)
        assertEquals(setOf("y", "z") to setOf("x", "y"), tr.write to tr.delete)
    }

    @Test fun routinesFreshAddReplaceAndTwice() {
        val here = listOf(Routine("here-1", "Good morning", RoutineTrigger.At(LocalTime.of(8, 0), java.time.DayOfWeek.entries.toSet()),
            listOf(RoutineStep.Say("Hi")), created = 1))
        val file = BackupKit.routines()
        var n = 0
        val add = RestoreMerge.routines(here, RoutineSettings(), emptyList(), file, RoutineSettings(briefLock = app.heyrobot.core.BriefLock.EVERYTHING),
            listOf(BriefReminder("Bring the passport", LocalDate.of(2026, 10, 13))), RestoreMode.ADD, mine = false, devices = { "dev-9" }, newId = { "new-${n++}" })
        assertEquals(3, add.routines.size)
        // The name taken here: " (restored)"; every one off from a file that isn't yours; the device's new id.
        assertEquals("Good morning (restored)", add.routines[1].name)
        assertTrue(add.routines.drop(1).none { it.enabled })
        assertEquals("dev-9", (add.routines[2].trigger as RoutineTrigger.Device).deviceId)
        // Add keeps this phone's brief settings. Someone else's brief reminders don't come: the brief says them as
        // Robot's own words (this pin was 1 before they were left out).
        assertEquals(RoutineSettings(), add.settings)
        assertEquals(0, add.reminders.size)
        val again = RestoreMerge.routines(add.routines, add.settings, add.reminders, file, null, add.reminders, RestoreMode.ADD, mine = false, devices = { "dev-9" })
        assertEquals(0, again.added)
        assertEquals(add.routines, again.routines)
        assertEquals(add.reminders, again.reminders)
        val replace = RestoreMerge.routines(here, RoutineSettings(), emptyList(), file, RoutineSettings(briefLock = app.heyrobot.core.BriefLock.EVERYTHING),
            emptyList(), RestoreMode.REPLACE, mine = true, devices = { it })
        assertEquals(file.map { it.id }, replace.routines.map { it.id })
        assertEquals(app.heyrobot.core.BriefLock.EVERYTHING, replace.settings.briefLock)
        // Brief reminders come only from your own backup: Add adds the ones this phone hasn't, Replace takes the file's;
        // from someone else's, this phone's stay as they are, whatever the mode.
        val passport = BriefReminder("Bring the passport", LocalDate.of(2026, 10, 13))
        val keys = BriefReminder("Keys for Marie", LocalDate.of(2026, 10, 14))
        fun reminders(mode: RestoreMode, mine: Boolean) = RestoreMerge.routines(here, RoutineSettings(), listOf(keys), file, null, listOf(passport, keys), mode,
            mine, devices = { it }, newId = { "new-${n++}" }).reminders
        assertEquals(listOf(keys, passport), reminders(RestoreMode.ADD, mine = true))
        assertEquals(listOf(passport, keys), reminders(RestoreMode.REPLACE, mine = true))
        for (mode in RestoreMode.entries) assertEquals(mode.name, listOf(keys), reminders(mode, mine = false))
        // A routine whose device didn't land arrives off, and says why.
        val gone = RestoreMerge.routines(emptyList(), RoutineSettings(), emptyList(), file, null, emptyList(), RestoreMode.FRESH, mine = true, devices = { null })
        assertFalse(gone.routines.first { it.id == BackupKit.ROUTINE_HEADSET }.enabled)
        assertEquals(RoutineOff.DEVICE_GONE, gone.off[BackupKit.ROUTINE_HEADSET])
        assertTrue(gone.routines.first { it.id == BackupKit.ROUTINE_TIME }.enabled)
    }

    @Test fun headsetCarAndPlacesFreshAddReplaceAndTwice() {
        val here = DeviceMemory(listOf(RememberedDevice(1, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "My car", tail = "11:22", address = "00:00:00:00:11:22", private = false)), 2)
        val files = BackupKit.content().getValue(BackupCategory.HANDSFREE).mapValues { String(it.value) }
        val add = RestoreMerge.handsfree(here, HeadsetFile(), CarFile(), Places(work = SavedPlace.of("Work", 1.0, 1.0)), files, RestoreMode.ADD, mine = false)
        assertEquals(listOf("dev-1", "dev-2"), add.devices.devices.map { it.id })
        assertEquals("dev-2", add.landed["dev-1"])
        // Asked again: "only you can hear it".
        assertNull(add.devices.devices[1].private)
        // Home where this phone had none; Work stays this phone's; the settings stay this phone's.
        assertEquals("Home", add.places.home?.label)
        assertEquals("Work", add.places.work?.label)
        assertEquals(HeadsetFile(), add.headset)
        val again = RestoreMerge.handsfree(add.devices, add.headset, add.car, add.places, files, RestoreMode.ADD, mine = false)
        assertEquals(0, again.added)
        assertEquals(add.devices, again.devices)
        assertEquals("dev-2", again.landed["dev-1"])
        val replace = RestoreMerge.handsfree(here, HeadsetFile(), CarFile(), Places(), files, RestoreMode.REPLACE, mine = true)
        // The backup's dev-1 is another device than this phone's: it takes this phone's next number (this pin was dev-1,
        // this phone's car's number, before Replace numbered them as Add does).
        assertEquals(listOf("dev-2"), replace.devices.devices.map { it.id })
        assertEquals("dev-2", replace.landed["dev-1"])
        assertTrue(replace.car.on)
        assertFalse(replace.headset.safety.personalOnlyPrivate)
        // From someone else, Replace keeps the hands-free safety rows.
        val notMine = RestoreMerge.handsfree(here, HeadsetFile(), CarFile(), Places(), files, RestoreMode.REPLACE, mine = false)
        assertTrue(notMine.headset.safety.personalOnlyPrivate)
        assertTrue(SaferField.HANDSFREE_PRIVATE in notMine.kept)
    }

    /**
     * From a backup that isn't yours, someone you confirmed never changes. An entry with their number and an email they
     * never had stays apart, unconfirmed (a card before any text or email), and what the file links to it follows it; one
     * that adds nothing to them lands on them. Restored twice, nothing more is added.
     */
    @Test fun someoneYouConfirmedNeverTakesAnotherBackupsAddresses() {
        val phone = ContactPhone("+1 514 555 0111", PhoneKind.MOBILE, "Mobile")
        val mom = Person("here-mom", "Mom", phones = listOf(phone))
        val jane = Person("p-jane", "Jane", nicknames = listOf("Mom"), phones = listOf(phone), emails = listOf("mom.jane@elsewhere.example"))
        val r = RestoreMerge.people(listOf(mom), listOf(jane), RestoreMode.ADD, mine = false, now = 5)
        assertEquals(mom, r.people.first { it.id == "here-mom" })
        val apart = r.people.single { it.id != "here-mom" }
        assertFalse(apart.confirmed)
        assertEquals(listOf("mom.jane@elsewhere.example"), apart.emails)
        assertEquals(apart.id, r.landed["p-jane"])
        assertEquals(listOf(1, 0, 0), listOf(r.added, r.updated, r.skipped))
        val again = RestoreMerge.people(r.people, listOf(jane), RestoreMode.ADD, mine = false, now = 6)
        assertEquals(r.people, again.people)
        assertEquals(0, again.added)
        assertEquals(apart.id, again.landed["p-jane"])
        // Their names alone, or an entry that adds nothing to them: it lands on them, and they stay as they were.
        val same = RestoreMerge.people(listOf(mom), listOf(Person("p-mom", "Mom", phones = listOf(phone))), RestoreMode.ADD, mine = false, now = 5)
        assertEquals(listOf(mom), same.people)
        assertEquals("here-mom", same.landed["p-mom"])
        // A name they don't have, with their number: apart too.
        val named = RestoreMerge.people(listOf(mom), listOf(Person("p-x", "Mom", aliases = listOf("boss"), phones = listOf(phone))), RestoreMode.ADD, mine = false, now = 5)
        assertEquals(mom, named.people.first { it.id == "here-mom" })
        assertEquals(2, named.people.size)
        // Someone not confirmed here still takes what the file adds (and stays unconfirmed): the card shows it first.
        val learned = mom.copy(confirmed = false)
        val joined = RestoreMerge.people(listOf(learned), listOf(jane), RestoreMode.ADD, mine = false, now = 5)
        assertEquals(listOf("here-mom"), joined.people.map { it.id })
        assertFalse(joined.people.single().confirmed)
        assertTrue("mom.jane@elsewhere.example" in joined.people.single().emails)
        // Yours: merged into them, as an import does.
        val mine = RestoreMerge.people(listOf(mom), listOf(jane), RestoreMode.ADD, mine = true, now = 5)
        assertEquals(listOf("here-mom"), mine.people.map { it.id })
        assertTrue("mom.jane@elsewhere.example" in mine.people.single().emails)
        // An XMPP address whose local part is their number, on a server that isn't the SMS gateway: People's merge reads
        // it as that number, so it matches them, and it stays apart all the same.
        val stranger = Person("p-x2", "Mom", xmpp = listOf(app.heyrobot.core.XmppAddress("+15145550111@elsewhere.example")))
        val viaServer = RestoreMerge.people(listOf(mom), listOf(stranger), RestoreMode.ADD, mine = false, now = 5)
        assertEquals(mom, viaServer.people.first { it.id == "here-mom" })
        assertFalse(viaServer.people.single { it.id != "here-mom" }.confirmed)
        assertTrue(viaServer.landed["p-x2"] != "here-mom")
        // A second entry that matches only through what a first one would have added stays apart too, and lands there.
        val email = Person("p-e", "Jane", emails = listOf("mom.jane@elsewhere.example"))
        val two = RestoreMerge.people(listOf(mom), listOf(jane, email), RestoreMode.ADD, mine = false, now = 5)
        assertEquals(mom, two.people.first { it.id == "here-mom" })
        assertEquals(2, two.people.size)
        assertEquals(two.landed["p-jane"], two.landed["p-e"])
    }

    /**
     * A category empty when the preview opened is restored as FRESH: whatever was added to it since stays, as Add keeps
     * it. On one still empty, FRESH writes the backup's as Replace would.
     */
    @Test fun freshKeepsWhatWasAddedSinceThePreview() {
        val zoe = Person("here-2", "Zoé", emails = listOf("zoe@example.org"))
        val people = BackupKit.people()
        assertEquals(listOf("here-2") + people.map { it.id }, RestoreMerge.people(listOf(zoe), people, RestoreMode.FRESH, mine = true, now = 5).people.map { it.id })
        val notes = RestoreMerge.byUpdated(mapOf("x" to 1L), mapOf("a" to 2L), RestoreMode.FRESH)
        assertEquals(setOf("a") to emptySet<String>(), notes.write to notes.delete)
        val t = RestoreMerge.transcripts(setOf("x"), setOf("a"), RestoreMode.FRESH)
        assertEquals(setOf("a") to emptySet<String>(), t.write to t.delete)
        val mine = Routine("here-1", "Good morning", RoutineTrigger.At(LocalTime.of(8, 0), java.time.DayOfWeek.entries.toSet()), listOf(RoutineStep.Say("Hi")), created = 1)
        val routines = RestoreMerge.routines(listOf(mine), RoutineSettings(), emptyList(), BackupKit.routines(),
            RoutineSettings(briefLock = app.heyrobot.core.BriefLock.EVERYTHING), emptyList(), RestoreMode.FRESH, mine = true, devices = { it })
        assertEquals("here-1", routines.routines.first().id)
        assertEquals(1 + BackupKit.routines().size, routines.routines.size)
        assertEquals(RoutineSettings(), routines.settings)
        val home = app.heyrobot.core.skills.SkillFixtures.headingHome()
        val pasta = app.heyrobot.core.skills.SkillFixtures.pasta()
        assertEquals(listOf(pasta.id, home.id), RestoreMerge.skills(listOf(pasta), listOf(home), RestoreMode.FRESH, mine = true, newId = { "s-new001" }).skills.map { it.id })
        val car = RememberedDevice(1, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "My car", tail = "11:22", address = "00:00:00:00:11:22", private = false)
        val hfFiles = BackupKit.content().getValue(BackupCategory.HANDSFREE).mapValues { String(it.value) }
        val hf = RestoreMerge.handsfree(DeviceMemory(listOf(car), 2), HeadsetFile(), CarFile(), Places(), hfFiles, RestoreMode.FRESH, mine = true)
        assertEquals(listOf("dev-1", "dev-2"), hf.devices.devices.map { it.id })
        assertEquals(CarFile(), hf.car)
        val files = RestoreMerge.files(index(fileEntry(9)), index(fileEntry(1)), RestoreMode.FRESH, mine = true, now = 9, people = { it })
        assertEquals(listOf(fid(1)) to emptySet<Any>(), files.add.map { it.id } to files.delete)
        val drawers = app.heyrobot.core.files.FileViews.DRAWERS_KEY
        assertEquals(listOf("House", "Car"), FilesMerge.extra(mapOf(drawers to listOf("House")), mapOf(drawers to listOf("Car")), RestoreMode.FRESH, mine = true,
            rethumb = emptyList())[drawers])

        // Still empty: the same as Replace.
        for (yours in listOf(false, true)) {
            fun ppl(m: RestoreMode) = RestoreMerge.people(emptyList(), people, m, yours, now = 5).let { it.people to it.landed }
            assertEquals(ppl(RestoreMode.REPLACE), ppl(RestoreMode.FRESH))
            fun rt(m: RestoreMode) = RestoreMerge.routines(emptyList(), RoutineSettings(), emptyList(), BackupKit.routines(),
                RoutineSettings(briefLock = app.heyrobot.core.BriefLock.EVERYTHING), listOf(BriefReminder("Bring the passport", LocalDate.of(2026, 10, 13))), m,
                yours, devices = { it }).let { listOf(it.routines, it.settings, it.reminders, it.off) }
            assertEquals(rt(RestoreMode.REPLACE), rt(RestoreMode.FRESH))
            fun sk(m: RestoreMode) = RestoreMerge.skills(emptyList(), listOf(home), m, yours, newId = { "s-new001" }, fileSettings = app.heyrobot.core.skills.SkillSettings(modelPicks = true))
                .let { listOf(it.skills, it.settings, it.kept) }
            assertEquals(sk(RestoreMode.REPLACE), sk(RestoreMode.FRESH))
            fun hands(m: RestoreMode) = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(), hfFiles, m, yours)
                .let { listOf(it.devices, it.landed, it.headset, it.car, it.places, it.kept) }
            assertEquals(hands(RestoreMode.REPLACE), hands(RestoreMode.FRESH))
            val idx = index(fileEntry(1, thumb = true), fileEntry(2))
            fun fl(m: RestoreMode) = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), idx, m, yours, now = 9, people = { it })
                .let { listOf(it.add, it.delete, it.keep, it.rethumb, it.skipped) }
            assertEquals(fl(RestoreMode.REPLACE), fl(RestoreMode.FRESH))
            val file = mapOf(drawers to listOf("Car", "House"), "later" to "theirs")
            assertEquals(FilesMerge.extra(emptyMap(), file, RestoreMode.REPLACE, yours, emptyList()), FilesMerge.extra(emptyMap(), file, RestoreMode.FRESH, yours, emptyList()))
        }
    }


    /**
     * Replace and a phone with no devices number the backup's devices after this phone's, as Add does: a routine here
     * that waits for one of this phone's devices (or one forgotten here) never waits for the backup's instead. A device
     * this phone remembers too keeps its number, so its routines here still find it.
     */
    @Test fun aBackupsDeviceNeverTakesTheNumberOfOneHere() {
        val car = RememberedDevice(1, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "My car", tail = "11:22", address = "00:00:00:00:11:22", private = false)
        val buds = RememberedDevice(2, DeviceRole.HEADSET, DeviceFamily.BLUETOOTH, "Buds", tail = "33:44", address = "00:00:00:00:33:44", private = true)
        // dev-3 and dev-4 were forgotten here: a routine may still wait for them.
        val here = DeviceMemory(listOf(car, buds), 5)
        val theirCar = RememberedDevice(1, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "Their car", tail = "55:66", address = "00:00:00:00:55:66", private = false)
        val theirBuds = buds.copy(number = 3, name = "Pixel Buds")
        val files = mapOf(app.heyrobot.core.output.HandsFreeStore.DEVICES to DeviceMemory(listOf(theirCar, theirBuds), 4).encode())
        for (mine in listOf(false, true)) {
            val r = RestoreMerge.handsfree(here, HeadsetFile(), CarFile(), Places(), files, RestoreMode.REPLACE, mine)
            assertEquals(listOf("dev-5", "dev-2"), r.devices.devices.map { it.id })
            assertEquals(mapOf("dev-1" to "dev-5", "dev-3" to "dev-2"), r.landed)
            // This phone's car went with Replace; nothing else answers to its number.
            assertNull(r.devices.byId("dev-1"))
            assertEquals("Pixel Buds", r.devices.byId("dev-2")!!.name)
            assertEquals(6, r.devices.nextNumber)
            // A routine of the backup's follows its device to its new number.
            val routine = Routine("r-car", "Drive", RoutineTrigger.Device(app.heyrobot.core.DeviceKind.CAR, "dev-1"), listOf(RoutineStep.Say("Hi")), created = 1)
            val back = RestoreMerge.routines(emptyList(), RoutineSettings(), emptyList(), listOf(routine), null, emptyList(), RestoreMode.FRESH, mine,
                devices = { r.landed[it] })
            assertEquals("dev-5", (back.routines.single().trigger as RoutineTrigger.Device).deviceId)
        }
        // None here now, numbers used before: the backup's start after them; the larger next number is kept.
        val empty = RestoreMerge.handsfree(DeviceMemory(emptyList(), 5), HeadsetFile(), CarFile(), Places(), files, RestoreMode.FRESH, mine = true)
        assertEquals(listOf("dev-5", "dev-6"), empty.devices.devices.map { it.id })
        assertEquals(7, empty.devices.nextNumber)
        val later = mapOf(app.heyrobot.core.output.HandsFreeStore.DEVICES to DeviceMemory(listOf(theirCar), 9).encode())
        assertEquals(9, RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(), later, RestoreMode.FRESH, mine = true).devices.nextNumber)
    }

    @Test fun keysOnlyWhereThisPhoneHasNone() {
        val file = listOf("helper/claude", "helper/gone", "search/brave", "search/kagi")
        val plan = RestoreMerge.keys(file, setOf("claude"), { it == "search/kagi" }, mine = true)
        assertEquals(listOf("helper/claude", "search/brave"), plan.save)
        assertEquals(mapOf("helper/gone" to KeySkip.NO_SUCH_HELPER, "search/kagi" to KeySkip.ALREADY_HERE), plan.skipped)
        assertTrue(RestoreMerge.keys(file, setOf("claude"), { false }, mine = false).save.isEmpty())
        // Twice: they are here now.
        assertTrue(RestoreMerge.keys(file, setOf("claude"), { true }, mine = true).save.isEmpty())
    }

    @Test fun settingsAreReplacedNotMerged() {
        val s = RestoreMerge.settings(SettingsNow(), BackupKit.content().getValue(BackupCategory.SETTINGS).mapValues { String(it.value) }, mine = true, people = { it })
        assertEquals(BackupCategory.SETTINGS_FILES.keys, s.files.keys)
        // A file the backup lacks isn't touched.
        val only = RestoreMerge.settings(SettingsNow(), mapOf("look.json" to "{\"v\":1,\"classic\":true}"), mine = false, people = { it })
        assertEquals(setOf("look.json"), only.files.keys)
        assertEquals(SettingsNow().settings, only.settings)
        // Wake phrases are cleaned; none left keeps this phone's.
        val bad = RobotSettings().toJson().put("wakePhrases", org.json.JSONArray().put(org.json.JSONObject().put("id", 1).put("text", "!!").put("sensitivity", "MEDIUM").put("enabled", true)))
        val w = RestoreMerge.settings(SettingsNow(), mapOf("settings.json" to bad.toString()), mine = true, people = { it })
        assertEquals(RobotSettings().wakePhrases, w.settings.wakePhrases)
    }

    /** 1.1, Skills (plan §4): Replace takes the file's (at most 100); Add the ones this phone hasn't, renamed when taken; not yours, off. */
    @Test fun skillsReplaceAndAdd() {
        val home = app.heyrobot.core.skills.SkillFixtures.headingHome(level = app.heyrobot.core.skills.SkillLevel.JUST_DO_IT)
        val pasta = app.heyrobot.core.skills.SkillFixtures.pasta()
        val ids = generateSequence(1) { it + 1 }.map { "s-new" + it.toString().padStart(3, '0') }.iterator()
        val replace = RestoreMerge.skills(listOf(pasta), listOf(home), RestoreMode.REPLACE, mine = true, newId = { ids.next() })
        assertEquals(listOf(home), replace.skills)
        val theirs = RestoreMerge.skills(emptyList(), listOf(home), RestoreMode.FRESH, mine = false, newId = { ids.next() })
        assertEquals(app.heyrobot.core.skills.SkillLevel.OFF, theirs.skills.single().level)
        assertEquals(1, theirs.off)
        // Add: the same skill again adds nothing; another with a taken name and id is renamed and gets a new id.
        assertEquals(0, RestoreMerge.skills(listOf(home), listOf(home), RestoreMode.ADD, mine = true, newId = { ids.next() }).added)
        val other = app.heyrobot.core.skills.SkillFixtures.simple(home.id, "Heading home")
        val added = RestoreMerge.skills(listOf(home), listOf(other), RestoreMode.ADD, mine = false, newId = { ids.next() })
        assertEquals(1, added.added)
        val r = added.skills.last()
        assertEquals("Heading home (restored)", r.name)
        assertEquals(emptyList<String>(), r.namesEn)
        assertTrue(r.id != home.id)
        assertEquals(app.heyrobot.core.skills.SkillLevel.OFF, r.level)
        assertTrue(app.heyrobot.core.skills.SkillCheck.valid(r))
        // The cap stays 100.
        val many = (0 until 100).map { app.heyrobot.core.skills.SkillFixtures.simple("s-" + it.toString().padStart(6, '0'), "Timer number $it") }
        assertEquals(100, RestoreMerge.skills(many, listOf(pasta), RestoreMode.ADD, mine = true, newId = { ids.next() }).skills.size)
        assertEquals(100, RestoreMerge.skills(emptyList(), many + pasta, RestoreMode.REPLACE, mine = true, newId = { ids.next() }).skills.size)
        // Phase B: skill_settings.json. Replace takes the file's, its switch kept here when looser and not yours; Add keeps this phone's.
        val off = app.heyrobot.core.skills.SkillSettings(modelPicks = false)
        val on = app.heyrobot.core.skills.SkillSettings(modelPicks = true)
        val notYours = RestoreMerge.skills(emptyList(), listOf(home), RestoreMode.REPLACE, mine = false, newId = { ids.next() }, haveSettings = off, fileSettings = on)
        assertEquals(off, notYours.settings)
        assertEquals(listOf(SaferField.SKILLS_MODEL_PICKS), notYours.kept)
        assertEquals(on, RestoreMerge.skills(emptyList(), listOf(home), RestoreMode.REPLACE, mine = true, newId = { ids.next() }, haveSettings = off,
            fileSettings = on).settings)
        assertEquals(null, RestoreMerge.skills(emptyList(), listOf(home), RestoreMode.REPLACE, mine = true, newId = { ids.next() }).settings)
        assertEquals(null, RestoreMerge.skills(listOf(pasta), listOf(home), RestoreMode.ADD, mine = true, newId = { ids.next() }, fileSettings = off).settings)
    }

    /** Fix 1.1: a backup's skill with a later Robot's languages keeps them through Replace and Add, renamed or not. */
    @Test fun skillsKeepMoreLanguages() {
        val more = app.heyrobot.core.skills.SkillFixtures.headingHomeMore()
        val ids = generateSequence(1) { it + 1 }.map { "s-new" + it.toString().padStart(3, '0') }.iterator()
        for (mode in listOf(RestoreMode.REPLACE, RestoreMode.FRESH))
            assertEquals(mode.name, more.copy(level = app.heyrobot.core.skills.SkillLevel.OFF),
                RestoreMerge.skills(emptyList(), listOf(more), mode, mine = false, newId = { ids.next() }).skills.single())
        val home = app.heyrobot.core.skills.SkillFixtures.headingHome()
        val renamed = RestoreMerge.skills(listOf(home), listOf(more.copy(steps = more.steps.take(1))), RestoreMode.ADD, mine = true, newId = { ids.next() }).skills.last()
        assertEquals("Heading home (restored)", renamed.name)
        assertEquals(more.namesMore, renamed.namesMore)
        assertEquals(more.params[0].askMore, renamed.params[0].askMore)
        // And through the file the restore writes.
        val text = app.heyrobot.core.skills.SkillCodec.encode(listOf(renamed))
        assertEquals(app.heyrobot.core.skills.SkillFile.Ok(listOf(renamed)), app.heyrobot.core.skills.SkillCodec.decode(text))
    }

    // ---------------------------------------------------------------------------------------------- 1.4, Robot Files

    private fun fileEntry(n: Int, sha: Int = n, thumb: Boolean = false, people: List<String> = emptyList()) = app.heyrobot.core.files.FileEntry(
        app.heyrobot.core.files.FileId.of("%032x".format(n))!!, "file $n", app.heyrobot.core.files.FileKind.RECEIPT, app.heyrobot.core.files.FileState.FILED,
        app.heyrobot.core.files.Provenance(app.heyrobot.core.files.FileSource.SHARE, 1, 10, "%064x".format(sha)), drawer = "receipts", thumb = thumb, people = people)
    private fun fid(n: Int) = app.heyrobot.core.files.FileId.of("%032x".format(n))!!
    private fun index(vararg e: app.heyrobot.core.files.FileEntry, extra: Map<String, Any?> = emptyMap()) = app.heyrobot.core.files.FileIndex(e.toList(), extra)

    /** Files, Add: by id, and never a second copy of bytes already here; a second restore adds nothing; 5,000 at most. */
    @Test fun filesAdd() {
        val have = index(fileEntry(1), fileEntry(2, sha = 20))
        val file = index(fileEntry(1, sha = 99), fileEntry(3, sha = 20), fileEntry(4, thumb = true), fileEntry(5))
        val add = RestoreMerge.files(have, file, RestoreMode.ADD, mine = true, now = 9, people = { it })
        assertEquals(listOf(fid(4), fid(5)), add.add.map { it.id })
        assertEquals(emptySet<Any>(), add.delete)
        assertEquals(2, add.skipped)
        assertEquals(listOf(fid(4)), add.rethumb)
        assertEquals(setOf(fid(4), fid(5)), add.staged)
        // Restored again: everything is here now.
        val again = RestoreMerge.files(index(*(have.entries + add.add).toTypedArray()), file, RestoreMode.ADD, mine = true, now = 9, people = { it })
        assertTrue(again.add.isEmpty())
        // The cap: room for one more, the backup's first that is new here.
        val full = app.heyrobot.core.files.FileIndex((10 until 10 + app.heyrobot.core.files.FileLimits.MAX_FILES - 1).map { fileEntry(it, sha = 100_000 + it) })
        val capped = RestoreMerge.files(full, file, RestoreMode.ADD, mine = true, now = 9, people = { it })
        assertEquals(listOf(fid(1)), capped.add.map { it.id })
        assertEquals(3, capped.skipped)
    }

    /** Files, Replace: this phone's go; the same id with the same bytes keeps them (never deleted and restored at once). */
    @Test fun filesReplaceAndFresh() {
        val have = index(fileEntry(1, thumb = true), fileEntry(2), fileEntry(3, sha = 30))
        val file = index(fileEntry(1, thumb = true), fileEntry(3, sha = 31), fileEntry(4, thumb = true))
        val r = RestoreMerge.files(have, file, RestoreMode.REPLACE, mine = true, now = 9, people = { it })
        assertEquals(listOf(fid(1), fid(4)), r.add.map { it.id })
        assertEquals(setOf(fid(1)), r.keep)
        assertEquals(setOf(fid(2), fid(3)), r.delete)
        assertTrue(r.delete.none { d -> r.add.any { it.id == d } })
        assertEquals(setOf(fid(4)), r.staged)
        assertEquals(listOf(fid(4)), r.rethumb)
        assertEquals(1, r.skipped)
        val fresh = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), file, RestoreMode.FRESH, mine = true, now = 9, people = { it })
        assertEquals(listOf(fid(1), fid(3), fid(4)), fresh.add.map { it.id })
        assertEquals(emptySet<Any>(), fresh.delete)
        // No thumbnail is carried: each comes back without one, listed to be made again.
        assertTrue(fresh.add.none { it.thumb })
        assertEquals(listOf(fid(1), fid(4)), fresh.rethumb)
    }

    /** A file's People links follow where each person landed; a link to nobody is dropped. */
    @Test fun filesPeopleLinksFollowWhereEachLanded() {
        val file = index(fileEntry(1, people = listOf("p-1", "p-2")))
        val r = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), file, RestoreMode.FRESH, mine = true, now = 9, people = { if (it == "p-1") "p-7" else null })
        assertEquals(listOf("p-7"), r.add.single().people)
    }

    /** The index's top-level keys: your drawers joined on Add, the backup's on Replace; never someone else's; thumbnails listed. */
    @Test fun filesTopLevelKeys() {
        val drawers = app.heyrobot.core.files.FileViews.DRAWERS_KEY
        val have = mapOf(drawers to listOf("House"), "later" to "mine", FilesBackup.RETHUMB to listOf(fid(1).hex))
        val file = mapOf(drawers to listOf("Car", "House"), "later" to "theirs", "newer" to 1L)
        val add = FilesMerge.extra(have, file, RestoreMode.ADD, mine = true, rethumb = listOf(fid(2)))
        assertEquals(listOf("House", "Car"), add[drawers])
        assertEquals("mine", add["later"])
        assertEquals(1L, add["newer"])
        assertEquals(listOf(fid(1).hex, fid(2).hex), add[FilesBackup.RETHUMB])
        val replace = FilesMerge.extra(have, file, RestoreMode.REPLACE, mine = true, rethumb = emptyList())
        assertEquals(listOf("Car", "House"), replace[drawers])
        assertEquals("theirs", replace["later"])
        val theirs = FilesMerge.extra(have, file, RestoreMode.REPLACE, mine = false, rethumb = emptyList())
        assertEquals(have, theirs)
    }

    /** This phone's files: the key gone means Replace (they can't be read again); a list that can't be read waits for Start over. */
    @Test fun filesOnAPhoneThatCantTakeThem() {
        val v = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords()))
        val goneHere = PhoneHas(mapOf(BackupCategory.FILES to 212), files = FilesHere.KEY_GONE)
        // Restoring Files would delete them: off until you tick it (its line says how many go), the rest as usual.
        val untouched = RestorePlanner.plan(v, goneHere, RestorePlanner.defaults(v, goneHere), mine = true)
        assertEquals(PlanSkip.NOT_CHOSEN, untouched.of(BackupCategory.FILES).skip)
        assertTrue(untouched.of(BackupCategory.NOTES).restores)
        val gone = RestorePlanner.plan(v, goneHere, RestorePlanner.defaults(v, goneHere) + (BackupCategory.FILES to CategoryChoice()), mine = true)
        assertEquals(RestoreMode.REPLACE, gone.of(BackupCategory.FILES).mode)
        assertTrue(gone.of(BackupCategory.FILES).restores)
        // Nothing here to lose (its key gone over an empty cabinet): on, as anywhere.
        val emptyGone = PhoneHas(emptyMap(), files = FilesHere.KEY_GONE)
        assertTrue(RestorePlanner.plan(v, emptyGone, RestorePlanner.defaults(v, emptyGone), mine = true).of(BackupCategory.FILES).restores)
        assertTrue(RestorePlanner.defaults(v, PhoneHas(mapOf(BackupCategory.FILES to 3))).getValue(BackupCategory.FILES).restore)
        val bad = RestorePlanner.plan(v, PhoneHas(mapOf(BackupCategory.FILES to 3), files = FilesHere.UNREADABLE), RestorePlanner.defaults(v), mine = true)
        assertEquals(PlanSkip.FILES_UNREADABLE, bad.of(BackupCategory.FILES).skip)
        assertTrue(bad.of(BackupCategory.NOTES).restores)
        // F12: travel mode on here: Files stays as it is (a restore would replace or count the hidden drawers' files).
        val travel = RestorePlanner.plan(v, PhoneHas(mapOf(BackupCategory.FILES to 3), files = FilesHere.TRAVEL), RestorePlanner.defaults(v), mine = true)
        assertEquals(PlanSkip.FILES_TRAVEL, travel.of(BackupCategory.FILES).skip)
        assertTrue(travel.of(BackupCategory.NOTES).restores)
        val ok = RestorePlanner.plan(v, PhoneHas(mapOf(BackupCategory.FILES to 3)), RestorePlanner.defaults(v), mine = true)
        assertEquals(RestoreMode.ADD, ok.of(BackupCategory.FILES).mode)
        assertEquals(4L, ok.of(BackupCategory.FILES).inBackup)
    }
}
