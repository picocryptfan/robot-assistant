// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryRestore
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Design §7.9: what points at People, memory items and devices follows where each landed (a link to nobody is
 * dropped), and this phone's own values (its calendar, its voiceprint) stay.
 */
class RestoreRefsTest {
    private val here = Person("here-marie", "Marie Tremblay", phones = listOf(ContactPhone("+15145550101", PhoneKind.MOBILE, "Mobile")))

    @Test fun peopleLandingRemapsMemoryAndMessages() {
        // Marie is already here under another id; Bob is new, and a third id points at nobody.
        val people = RestoreMerge.people(listOf(here), BackupKit.people(), RestoreMode.ADD, mine = true, now = 1)
        assertEquals("here-marie", people.landed[BackupKit.MARIE])
        assertEquals(BackupKit.BOB, people.landed[BackupKit.BOB])
        val items = listOf(MemoryItem("m_aaaaaa", "Marie and Bob are neighbours", about = listOf(BackupKit.MARIE, BackupKit.BOB, "nobody"), createdAt = 1))
        val add = MemoryRestore.add(MemoryFile(), items, emptyList(), mine = true, now = 1, people = people.landed::get)
        assertEquals(listOf("here-marie", BackupKit.BOB), add.file.items.single().about)
        val replace = MemoryRestore.replace(MemoryFile(), items, emptyList(), mine = true, people = people.landed::get)
        assertEquals(listOf("here-marie", BackupKit.BOB), replace.file.items.single().about)
        val m = RestoreRefs.messages(MessagesFile(noCard = setOf(BackupKit.MARIE, "nobody"), replyLang = mapOf(BackupKit.BOB to Lang.FR, "nobody" to Lang.EN),
            announcePeople = setOf(BackupKit.MARIE)), people.landed::get)
        assertEquals(setOf("here-marie"), m.noCard)
        assertEquals(mapOf(BackupKit.BOB to Lang.FR), m.replyLang)
        assertEquals(setOf("here-marie"), m.announcePeople)
        // Through the settings step too.
        val staged = RestoreMerge.settings(SettingsNow(), mapOf(MessagesFile.FILE to MessagesFile(noCard = setOf(BackupKit.MARIE)).toJson()), mine = true,
            people = people.landed::get)
        assertEquals(setOf("here-marie"), MessagesFile.fromJson(staged.files[MessagesFile.FILE]).noCard)
        // Replace keeps ids: only those still there.
        val same = RestoreRefs.same(setOf(BackupKit.MARIE))
        assertEquals(BackupKit.MARIE, same(BackupKit.MARIE))
        assertNull(same(BackupKit.BOB))
    }

    @Test fun memoryIdsForHistory() {
        val c = StoredConversation("c", 1, 2, listOf(ChatTurn("1", TurnRole.USER, "q", 1, memoryIds = listOf("m_old001", "m_old002", "m_old003")),
            ChatTurn("2", TurnRole.ASSISTANT, "a", 2)))
        val moved = RestoreRefs.conversation(c, mapOf("m_old001" to "m_new001", "m_old002" to "m_here02")::get)
        assertEquals(listOf("m_new001", "m_here02"), moved.turns[0].memoryIds)
        assertEquals(c.turns[1], moved.turns[1])
        // Two old items landing on one: listed once.
        assertEquals(listOf("m_one"), RestoreRefs.conversation(c, { "m_one" }).turns[0].memoryIds)
        // Memory's own landing gives the map (Add: new ids; the same words: the item here).
        val items = listOf(MemoryItem("m_old001", "Tea", createdAt = 1), MemoryItem("m_old002", "Coffee", createdAt = 1))
        val have = MemoryFile(items = listOf(MemoryItem("m_here02", "coffee", createdAt = 1)))
        val add = MemoryRestore.add(have, items, emptyList(), mine = true, now = 5, people = { it })
        assertEquals("m_here02", add.landed["m_old002"])
        val linked = RestoreRefs.conversation(c, add.landed::get)
        assertEquals(listOf(add.landed["m_old001"], "m_here02"), linked.turns[0].memoryIds)
    }

    @Test fun devicesForRoutines() {
        val files = BackupKit.content().getValue(BackupCategory.HANDSFREE).mapValues { String(it.value) }
        val existing = (app.heyrobot.core.output.DeviceMemory.decode(files["devices.json"]) as app.heyrobot.core.output.DeviceFile.Ok).memory
        // This phone already knows the headset as dev-4.
        val here = DeviceMemory(existing.devices.map { it.copy(number = 4) }, 5)
        val hf = RestoreMerge.handsfree(here, HeadsetFile(), CarFile(), Places(), files, RestoreMode.ADD, mine = true)
        assertEquals("dev-4", hf.landed["dev-1"])
        val r = RestoreRefs.routine(BackupKit.routines()[1], hf.landed::get)!!
        assertEquals("dev-4", (r.trigger as RoutineTrigger.Device).deviceId)
        // A routine at a time, or for any headset, is left as it is; one whose device didn't land is null (restored off).
        assertEquals(BackupKit.routines()[0], RestoreRefs.routine(BackupKit.routines()[0], { null }))
        val any = BackupKit.routines()[1].copy(trigger = RoutineTrigger.Device(app.heyrobot.core.DeviceKind.HEADSET, null))
        assertEquals(any, RestoreRefs.routine(any, { null }))
        assertNull(RestoreRefs.routine(BackupKit.routines()[1], { null }))
    }

    @Test fun calendarAndVoiceprintAreThisPhones() {
        val phone = RobotSettings()
        val s = RestoreRefs.phoneSpecific(phone, RobotSettings(eventCalendar = 42, meVoiceprint = true, wakeWord = false))
        assertEquals(app.heyrobot.core.CalendarPick.AUTO, s.eventCalendar)
        assertFalse(s.meVoiceprint)
        assertFalse(s.wakeWord)
    }
}
