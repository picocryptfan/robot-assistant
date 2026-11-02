// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.BriefItem
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineOutput
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.AfterReading
import app.heyrobot.core.messages.KeepMessages
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceFamily
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.CurrentInfo
import app.heyrobot.core.web.KeepSources
import app.heyrobot.core.web.ReadPages
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields of the 1 Nov audit's hostile-backup findings: a backup not marked yours keeps this phone's value; yours, as saved. */
class RestoreHostileFieldsTest {
    private fun <T> guarded(f: SaferField, phone: T, file: T, guard: (T, T, Boolean) -> Guarded<T>, get: (T) -> Any?) {
        val kept = guard(phone, file, false)
        assertEquals(f.name, get(phone), get(kept.value))
        assertTrue(f.name, f in kept.kept)
        val mine = guard(phone, file, true)
        assertEquals(f.name, get(file), get(mine.value))
        assertFalse(f.name, f in mine.kept)
    }

    private val settings: (RobotSettings, RobotSettings, Boolean) -> Guarded<RobotSettings> = { p, f, m -> RestoreSafety.settings(p, f, m) }
    private val web: (WebSettings, WebSettings, Boolean) -> Guarded<WebSettings> = RestoreSafety::web
    private val routines: (RoutineSettings, RoutineSettings, Boolean) -> Guarded<RoutineSettings> = RestoreSafety::routineSettings

    private fun car(enabled: Boolean = true) = RememberedDevice(1, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "A car", tail = "11:22", address = "00:00:00:00:11:22", enabled = enabled)

    @Test fun aRestoredCarStaysOffUntilYouTurnItOn() {
        assertFalse(RestoreTaint.device(car(), mine = false).enabled)
        assertTrue(RestoreTaint.device(car(), mine = true).enabled)
        assertTrue(RestoreTaint.device(car(), mine = false, here = car()).enabled)
        assertFalse(RestoreTaint.device(car(), mine = false, here = car(enabled = false)).enabled)
        val files = mapOf(HandsFreeStore.DEVICES to DeviceMemory(listOf(car()), 2).encode())
        for (mode in listOf(RestoreMode.ADD, RestoreMode.FRESH)) {
            val out = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(), files, mode, mine = false)
            assertFalse(out.devices.devices.single().enabled)
        }
    }

    @Test fun carModeAndStartingOnConnectStayOff() {
        val g: (CarFile, CarFile, Boolean) -> Guarded<CarFile> = RestoreSafety::car
        guarded(SaferField.CAR_ON, CarFile(on = false), CarFile(on = true), g) { it.on }
        guarded(SaferField.CAR_START, CarFile(settings = CarSettings(startOnConnect = false)), CarFile(settings = CarSettings(startOnConnect = true)), g) { it.settings.startOnConnect }
    }

    @Test fun someoneElsesBackupWithNoHomeOrWorkLeavesYours() {
        val here = Places(home = SavedPlace.of("Mine", 2.0, 2.0), work = SavedPlace.of("Job", 3.0, 3.0))
        for (add in listOf(false, true)) {
            val g = RestoreSafety.places(here, Places(), mine = false, add = add)
            assertEquals(here, g.value)
        }
        val files = mapOf(HandsFreeStore.PLACES to Places().encode())
        for (mode in listOf(RestoreMode.ADD, RestoreMode.FRESH, RestoreMode.REPLACE)) {
            assertEquals(mode.name, here, RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), here, files, mode, mine = false).places)
        }
        assertEquals("your own Replace is as the file says", Places(), RestoreSafety.places(here, Places(), mine = true, add = false).value)
        assertEquals("your own Add keeps what's here", here, RestoreSafety.places(here, Places(), mine = true, add = true).value)
    }

    @Test fun homeAndWorkComeFromYourOwnBackupOnly() {
        val home = SavedPlace.of("Theirs", 1.0, 1.0)
        val mineHere = SavedPlace.of("Mine", 2.0, 2.0)
        val g: (CarFile, CarFile, Boolean) -> Guarded<CarFile> = RestoreSafety::car
        guarded(SaferField.CAR_HOME, CarFile(), CarFile(settings = CarSettings(home = home)), g) { it.settings.home }
        guarded(SaferField.CAR_WORK, CarFile(), CarFile(settings = CarSettings(work = home)), g) { it.settings.work }
        val p: (Places, Places, Boolean) -> Guarded<Places> = { a, b, m -> RestoreSafety.places(a, b, m, add = false) }
        guarded(SaferField.PLACES_HOME, Places(), Places(home = home), p) { it.home }
        guarded(SaferField.PLACES_WORK, Places(), Places(work = home), p) { it.work }
        val files = mapOf(HandsFreeStore.PLACES to Places(home = home, work = home).encode())
        // Add, Fresh and Replace alike: this phone's, even none, and counted.
        for (mode in listOf(RestoreMode.ADD, RestoreMode.FRESH, RestoreMode.REPLACE)) {
            val out = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(work = mineHere), files, mode, mine = false)
            assertEquals(Places(work = mineHere), out.places)
            assertTrue(SaferField.PLACES_HOME in out.kept && SaferField.PLACES_WORK in out.kept)
        }
        val add = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(work = mineHere), files, RestoreMode.ADD, mine = true)
        assertEquals(Places(home, mineHere), add.places)
        val replace = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(work = mineHere), files, RestoreMode.REPLACE, mine = true)
        assertEquals(Places(home, home), replace.places)
    }

    @Test fun savingStaysOffWhereYouTurnedItOff() {
        val s = RobotSettings()
        guarded(SaferField.SAVE_HISTORY, s.copy(saveHistory = false), s.copy(saveHistory = true), settings) { it.saveHistory }
        guarded(SaferField.KEEP_MEDIA, s.copy(keepMedia = false), s.copy(keepMedia = true), settings) { it.keepMedia }
        val m: (MemorySettings, MemorySettings, Boolean) -> Guarded<MemorySettings> = RestoreSafety::memory
        guarded(SaferField.MEMORY_ON, MemorySettings(on = false), MemorySettings(on = true), m) { it.on }
        guarded(SaferField.MEMORY_TRANSCRIPTS, MemorySettings(transcripts = false), MemorySettings(transcripts = true), m) { it.transcripts }
        guarded(SaferField.WEB_KEEP_SOURCES, WebSettings(keepSources = KeepSources.NONE), WebSettings(keepSources = KeepSources.LINKS), web) { it.keepSources }
        guarded(SaferField.WEB_KEEP_SOURCES, WebSettings(keepSources = KeepSources.DOMAINS), WebSettings(keepSources = KeepSources.LINKS), web) { it.keepSources }
        // Keeping less than this phone is taken.
        assertEquals(KeepSources.NONE, RestoreSafety.web(WebSettings(keepSources = KeepSources.LINKS), WebSettings(keepSources = KeepSources.NONE), false).value.keepSources)
    }

    @Test fun theMicrophoneAndYourWakePhraseStayThisPhones() {
        val s = RobotSettings()
        guarded(SaferField.WAKE_WORD, s.copy(wakeWord = false), s.copy(wakeWord = true), settings) { it.wakeWord }
        guarded(SaferField.WAKE_CHARGING, s.copy(wakeOnlyWhileCharging = true), s.copy(wakeOnlyWhileCharging = false), settings) { it.wakeOnlyWhileCharging }
        guarded(SaferField.WAKE_PHRASES, s, s.copy(wakePhrases = emptyList()), settings) { it.wakePhrases }
    }

    @Test fun webSearchStaysOffAndGoesWhereItWent() {
        val w = WebSettings()
        guarded(SaferField.WEB_ON, w, w.copy(on = true), web) { it.on }
        guarded(SaferField.WEB_SERVICE, w, w.copy(service = SearchService.KAGI), web) { it.service }
        guarded(SaferField.WEB_SERVICE, w.copy(service = SearchService.MOJEEK), w.copy(service = SearchService.KAGI), web) { it.service }
        guarded(SaferField.WEB_ENCYCLOPEDIA, w.copy(encyclopedia = false), w.copy(encyclopedia = true), web) { it.encyclopedia }
        guarded(SaferField.WEB_BOOKS, w, w.copy(books = true), web) { it.books }
        guarded(SaferField.WEB_READ_PAGES, w.copy(readPages = ReadPages.NEVER), w.copy(readPages = ReadPages.ALWAYS), web) { it.readPages }
        guarded(SaferField.WEB_READ_THROUGH, w, w.copy(readThrough = app.heyrobot.core.web.ReadThrough.SERVICE), web) { it.readThrough }
        guarded(SaferField.WEB_ADD_TOWN, w, w.copy(addTown = true), web) { it.addTown }
        guarded(SaferField.WEB_CURRENT_INFO, w, w.copy(currentInfo = CurrentInfo.HELPER_FIRST), web) { it.currentInfo }
    }

    @Test fun yourOwnServerStaysAgainstRobotsDefault() {
        val s = RobotSettings()
        guarded(SaferField.NEAREST_SERVER, s.copy(nearestServer = "https://mine.example"), s, settings) { it.nearestServer }
        guarded(SaferField.SMS_GATEWAY, s.copy(smsGateway = "https://mine.example"), s, settings) { it.smsGateway }
        guarded(SaferField.WEB_SEARXNG, WebSettings(searxng = "https://mine.example"), WebSettings(), web) { it.searxng }
        guarded(SaferField.WEB_PUBLIC_SEARXNG, WebSettings(publicSearxng = "https://mine.example"), WebSettings(), web) { it.publicSearxng }
    }

    @Test fun theBriefStaysWhereAndWhatYouSet() {
        val r = RoutineSettings()
        guarded(SaferField.BRIEF_OUTPUT, r.copy(briefOutput = RoutineOutput.HEADSET_ONLY), r.copy(briefOutput = RoutineOutput.HEADSET_OR_SPEAKER), routines) { it.briefOutput }
        guarded(SaferField.BRIEF_OUTPUT, r.copy(briefOutput = RoutineOutput.SILENT), r.copy(briefOutput = RoutineOutput.HEADSET_ONLY), routines) { it.briefOutput }
        guarded(SaferField.BRIEF_ITEMS, r.copy(briefItems = listOf(BriefItem.GREETING)), r.copy(briefItems = listOf(BriefItem.GREETING, BriefItem.CALENDAR)), routines) { it.briefItems }
        guarded(SaferField.BRIEF_TRIGGER, r, r.copy(briefTrigger = RoutineTrigger.Charging()), routines) { it.briefTrigger }
        guarded(SaferField.BRIEF_ONLINE, r, r.copy(briefOnline = true), routines) { it.briefOnline }
        // Fewer items, or a quieter brief, are taken.
        assertEquals(listOf(BriefItem.GREETING), RestoreSafety.routineSettings(r, r.copy(briefItems = listOf(BriefItem.GREETING)), false).value.briefItems)
        assertEquals(RoutineOutput.SILENT, RestoreSafety.routineSettings(r, r.copy(briefOutput = RoutineOutput.SILENT), false).value.briefOutput)
    }

    @Test fun automaticUpdateChecksStayOff() {
        val g: (UpdateSettings, UpdateSettings, Boolean) -> Guarded<UpdateSettings> = RestoreSafety::updates
        guarded(SaferField.UPDATES_AUTO_CHECK, UpdateSettings(autoCheck = false), UpdateSettings(autoCheck = true), g) { it.autoCheck }
    }

    @Test fun yourDefaultHelperStays() {
        val a = HelperConfig.claude()
        val b = HelperConfig.of(Presets.OTHER, "jarvis", "af_heart", "Jarvis", "https://jarvis.example")
        val phone = RobotSettings(helpers = listOf(a, b), helpDefault = a.id)
        val file = phone.copy(helpDefault = b.id)
        guarded(SaferField.HELP_DEFAULT, phone, file, settings) { it.helpDefault }
        // None here: the file's. Its helper gone after the restore: the file's too.
        assertEquals(b.id, RestoreSafety.settings(phone.copy(helpDefault = null), file, false).value.helpDefault)
        assertEquals(b.id, RestoreSafety.settings(phone.copy(helpDefault = "gone"), file, false).value.helpDefault)
    }

    @Test fun aHelperYouHaveKeepsItsNamesAndWhatItMayDo() {
        val a = HelperConfig.claude().copy(search = false, actions = false, aliases = listOf("My one"))
        val file = a.copy(name = "Thanks", search = true, actions = true, aliases = listOf("Please"))
        val out = RestoreSafety.settings(RobotSettings(helpers = listOf(a)), RobotSettings(helpers = listOf(file)), false)
        assertEquals(a, out.value.helper(a.id))
        assertTrue(SaferField.HELPERS in out.kept)
        assertEquals(file, RestoreSafety.settings(RobotSettings(helpers = listOf(a)), RobotSettings(helpers = listOf(file)), true).value.helper(a.id))
    }

    @Test fun aNewHelpersNameIsChecked() {
        val named = { n: String -> HelperConfig.of(Presets.OTHER, "jarvis", "af_heart", n, "https://jarvis.example").copy(aliases = listOf("Please")) }
        val here = RobotSettings(helpers = listOf(HelperConfig.claude()))
        for (bad in listOf("Thanks", "Robot", "Claude")) {
            val h = RestoreSafety.settings(here, RobotSettings(helpers = here.helpers + named(bad)), false).value.helpers.last()
            assertTrue(bad, h.name != bad)
            assertEquals(emptyList<String>(), h.aliases)
        }
        val ok = RestoreSafety.settings(here, RobotSettings(helpers = here.helpers + named("Jarvis")), false).value.helpers.last()
        assertEquals("Jarvis", ok.name)
        assertEquals("Thanks", RestoreSafety.settings(here, RobotSettings(helpers = here.helpers + named("Thanks")), true).value.helpers.last().name)
    }

    @Test fun messagesKeepThisPhonesApps() {
        val g: (MessagesFile, MessagesFile, Boolean) -> Guarded<MessagesFile> = RestoreSafety::messages
        val m = { s: MessageSettings -> MessagesFile(settings = s) }
        val s = MessageSettings()
        val app = MessageApp.entries.first()
        guarded(SaferField.MESSAGES_ALLOWLIST, m(s), m(s.copy(allowlist = MessageAllowlist(setOf(app)))), g) { it.settings.allowlist }
        guarded(SaferField.MESSAGES_ALLOWLIST, m(s.copy(allowlist = MessageAllowlist(groups = false))), m(s.copy(allowlist = MessageAllowlist(groups = true))), g) { it.settings.allowlist }
        guarded(SaferField.MESSAGES_KEEP, m(s), m(s.copy(keep = KeepMessages.ONE_DAY)), g) { it.settings.keep }
        guarded(SaferField.MESSAGES_AFTER_READING, m(s), m(s.copy(afterReading = AfterReading.MARK_READ)), g) { it.settings.afterReading }
    }
}
