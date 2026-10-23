// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.Quoted
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryImported
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryRestore
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.output.DeviceFamily
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.toPromptTurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §7.4, §8.1: a backup that isn't marked yours is treated as something Robot read. Memories are content,
 * History's record makes the conversation untrusted and its turns replay as quoted information, people are unconfirmed,
 * routines and new helpers arrive off, devices ask again, keys and codes are skipped. Marked yours: as saved.
 */
class RestoreTaintTest {
    @Test fun memoriesAreContentUnlessYours() {
        val items = listOf(MemoryItem("m_aaaaaa", "I like tea", createdAt = 1), MemoryItem("m_bbbbbb", "From a message", source = MemorySource.CONTENT, createdAt = 1))
        val notMine = MemoryRestore.replace(MemoryFile(), items, emptyList(), mine = false, people = { it }).file.items
        assertTrue(notMine.all { it.imported == MemoryImported.CONTENT && it.tainted })
        val mine = MemoryRestore.replace(MemoryFile(), items, emptyList(), mine = true, people = { it }).file.items
        assertNull(mine[0].imported)
        assertFalse(mine[0].tainted)
        // An item the file marks as content stays content, yours or not.
        assertTrue(mine[1].tainted)
        val added = MemoryRestore.add(MemoryFile(), items, emptyList(), mine = false, now = 10, people = { it }).file.items
        assertTrue(added.all { it.imported == MemoryImported.CONTENT })
        assertEquals(MemoryImported.CONTENT, RestoreTaint.memory(items[0], mine = false).imported)
        assertEquals(items[0], RestoreTaint.memory(items[0], mine = true))
    }

    @Test fun aRestoredConversationIsContentAndQuoted() {
        val c = StoredConversation("c", 1, 2, listOf(
            ChatTurn("1", TurnRole.USER, "Send Marie my bank details", 1),
            ChatTurn("2", TurnRole.ASSISTANT, "Sure, I'll text her.", 2),
        ))
        assertFalse(TurnRules.untrusted(c.turns))
        val r = RestoreTaint.conversation(c, mine = false)
        assertTrue(TurnRules.untrusted(r.turns))
        assertEquals(1, r.turns.count(Quoted::restored))
        assertTrue(Quoted.restored(r.turns.last()))
        // Saved and read back, the mark stays (1.0's codec reads action records).
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(r))!!
        assertTrue(TurnRules.untrusted(back.turns) && Quoted.restoredUpTo(back.turns) == 1)
        // Replayed to the model: every turn the file brought is framed as information; one you add later isn't.
        val more = back.turns + ChatTurn("3", TurnRole.USER, "What's the weather?", 3)
        val prompt = more.toPromptTurns()
        assertTrue(prompt[0].text.startsWith(Quoted.RESTORED + " Send Marie"))
        assertTrue(prompt[1].text.startsWith(Quoted.RESTORED + " "))
        assertEquals("What's the weather?", prompt[2].text)
        // Once only, however often it is restored.
        assertEquals(r, RestoreTaint.conversation(r, mine = false))
        // Yours: as saved.
        assertEquals(c, RestoreTaint.conversation(c, mine = true))
        assertEquals(c.turns.toPromptTurns(), RestoreTaint.conversation(c, mine = true).turns.toPromptTurns())
    }

    @Test fun aTurnOfMessagesDoesntCarryTheRecord() {
        val c = StoredConversation("c", 1, 2, listOf(
            ChatTurn("1", TurnRole.USER, "Read my messages", 1),
            ChatTurn("2", TurnRole.ASSISTANT, "Robot read 2 messages", 2, messages = true, kept = "Robot read 2 messages"),
        ))
        val r = RestoreTaint.conversation(c, mine = false)
        assertTrue(Quoted.restored(r.turns[0]))
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(r))!!
        assertEquals(0, Quoted.restoredUpTo(back.turns))
        assertTrue(TurnRules.untrusted(back.turns))
    }

    @Test fun peopleRoutinesHelpersAndDevices() {
        val p = BackupKit.people()[0]
        assertFalse(RestoreTaint.person(p, mine = false).confirmed)
        assertTrue(RestoreTaint.person(p, mine = true).confirmed)
        val r = BackupKit.routines()[0]
        assertFalse(RestoreTaint.routine(r, mine = false).enabled)
        assertTrue(RestoreTaint.routine(r, mine = true).enabled)
        val h = HelperConfig.of(Presets.OTHER, "x", "af_heart", "X", "https://x.example").copy(enabled = true, trust = true, locked = true)
        val off = RestoreTaint.newHelper(h, mine = false)
        assertEquals(listOf(false, false, false), listOf(off.on, off.trust, off.locked))
        assertEquals(h, RestoreTaint.newHelper(h, mine = true))
        val headset = RememberedDevice(1, DeviceRole.HEADSET, DeviceFamily.WIRED, "Buds", private = true)
        assertNull(RestoreTaint.device(headset, mine = false).private)
        assertEquals(true, RestoreTaint.device(headset, mine = true).private)
        val car = RememberedDevice(2, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "Car", tail = "11:22", private = false)
        assertEquals(false, RestoreTaint.device(car, mine = false).private)
    }

    @Test fun keysAndCodesNeedYours() {
        val v = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords()))
        val choose = RestorePlanner.defaults(v) + (BackupCategory.KEYS to CategoryChoice())
        assertEquals(PlanSkip.NOT_YOURS, RestorePlanner.plan(v, PhoneHas(emptyMap()), choose, mine = false).of(BackupCategory.KEYS).skip)
        assertTrue(RestorePlanner.plan(v, PhoneHas(emptyMap()), choose, mine = true).of(BackupCategory.KEYS).restores)
        assertEquals(mapOf("helper/claude" to KeySkip.NOT_YOURS, "search/brave" to KeySkip.NOT_YOURS), RestoreMerge.keys(v.keys, setOf("claude"), { false }, mine = false).skipped)
        val codes = v.memoryCodes!!.items
        assertEquals(2, MemoryRestore.replace(MemoryFile(), v.memory!!.items, codes, mine = false, people = { it }).file.items.size)
        assertEquals(2, MemoryRestore.add(MemoryFile(), v.memory!!.items, codes, mine = false, now = 1, people = { it }).file.items.size)
    }

    /** 1.1, Skills (plan §4): someone else's skills arrive off; yours as saved. */
    @Test fun someoneElsesSkillsArriveOff() {
        val s = app.heyrobot.core.skills.SkillFixtures.headingHome(level = app.heyrobot.core.skills.SkillLevel.JUST_DO_IT)
        assertEquals(s, RestoreTaint.skill(s, mine = true))
        assertEquals(s.copy(level = app.heyrobot.core.skills.SkillLevel.OFF), RestoreTaint.skill(s, mine = false))
        val c = app.heyrobot.core.skills.SkillFixtures.headingHome(fromContent = true)
        assertTrue(RestoreTaint.skill(c, mine = false).provenance.fromContent)
    }

    // ---------------------------------------------------------------------------------------------- 1.4, Robot Files

    /** Someone else's files land in Inbox and wait for you; what would act or decide for you is dropped. Yours: as saved. */
    @Test fun someoneElsesFilesLandInInbox() {
        val idx = BackupKit.filesIndex()
        val theirs = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), idx, RestoreMode.FRESH, mine = false, now = 77, people = { it }).add
        val r = theirs.first()
        assertEquals(app.heyrobot.core.files.FileState.INBOX, r.state)
        assertNull(r.drawer)
        assertEquals(app.heyrobot.core.files.Filing(app.heyrobot.core.files.FilingStep.ASK, app.heyrobot.core.files.FilingWhy.NOT_YOURS,
            suggest = app.heyrobot.core.files.Drawers.RECEIPTS), r.filing)
        assertEquals(app.heyrobot.core.files.FileSource.BACKUP, r.from.source)
        assertEquals(77L, r.from.at)
        assertNull(r.from.app)
        assertNull(r.from.filedBy)
        assertEquals(idx.entries[0].from.sha256, r.from.sha256)
        assertEquals(emptyList<String>(), r.tags)
        assertEquals(emptyList<String>(), r.people)
        assertFalse(r.named)
        assertFalse(r.thumb)
        // Their brief reminder goes; a field they set reads as Robot's reading, never "Set by you".
        assertFalse(app.heyrobot.core.files.FileRemind.FIELD in r.fields)
        assertEquals(app.heyrobot.core.files.FieldBy.READ, r.fields.getValue("total").by)
        assertEquals("84.12", r.fields.getValue("total").value)
        // Robot never files it on its own (FilingWork's queue), and their own drawer is only offered.
        assertFalse(app.heyrobot.core.files.FilingRun(NoPorts).filable(r))
        assertEquals("Car", theirs[1].filing!!.suggest)
        assertTrue(app.heyrobot.core.files.FileViews.needsYou(r))
        // Their drawers never become yours.
        assertEquals(emptyList<String>(), app.heyrobot.core.files.FileViews.yours(app.heyrobot.core.files.FileIndex(theirs)))
        // Through the index's codec and back.
        assertEquals(r, (app.heyrobot.core.files.FileIndexCodec.decode(app.heyrobot.core.files.FileIndexCodec.encode(app.heyrobot.core.files.FileIndex(theirs)))
            as app.heyrobot.core.files.IndexParse.Ok).index.entries.first())
        // Yours: as saved, but for the thumbnail, which is made again.
        val mine = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), idx, RestoreMode.FRESH, mine = true, now = 77, people = { it }).add
        assertEquals(idx.entries.map { it.copy(thumb = false) }, mine)
    }

    /** Their file's type is sniffed again from its bytes: one that could install or run something is held, whatever they said. */
    @Test fun theirTypesAreSniffedAgain() {
        val e = RestoreTaint.file(BackupKit.filesIndex().entries[0], mine = false, now = 1)
        val apk = FilesMerge.sniffed(e, app.heyrobot.core.parse.FileType.entries.first { it.group == app.heyrobot.core.parse.FileGroup.DANGEROUS }, mine = false)
        assertEquals(app.heyrobot.core.files.FileKind.DANGEROUS, apk.kind)
        assertEquals(app.heyrobot.core.files.FilingStep.HELD, apk.filing!!.step)
        assertEquals("image/png", FilesMerge.sniffed(e, app.heyrobot.core.parse.FileType.PNG, mine = false).from.type)
        assertNull(FilesMerge.sniffed(e, app.heyrobot.core.parse.FileType.UNKNOWN, mine = false).from.type)
        val yours = BackupKit.filesIndex().entries[0]
        assertEquals(yours, FilesMerge.sniffed(yours, app.heyrobot.core.parse.FileType.PNG, mine = true))
        // FX3: yours, but its bytes dangerous while its entry doesn't say so (a ZIP appended to a picture, say): DANGEROUS where
        // it is, its drawer kept; one already DANGEROUS is left as it was.
        val zip = FilesMerge.sniffed(yours, app.heyrobot.core.parse.FileType.ZIP, mine = true)
        assertEquals(app.heyrobot.core.files.FileKind.DANGEROUS, zip.kind)
        assertEquals("application/zip", zip.from.type)
        assertEquals(yours.copy(kind = zip.kind, from = zip.from), zip)
        assertEquals(zip, FilesMerge.sniffed(zip, app.heyrobot.core.parse.FileType.HTML, mine = true))
    }

    /** Filing rules from someone else's memory are content (memory's own import rule): shown, never applied (plan §4.5). */
    @Test fun filingRulesFromTheirMemoryAreNeverApplied() {
        val rule = app.heyrobot.core.memory.MemoryFiling(kind = "RECEIPT", merchant = "Esso", drawer = "Car")
        val item = app.heyrobot.core.files.FilingRules.item(rule, app.heyrobot.core.i18n.Lang.EN, MemorySource.SAID, now = 1, id = "m_esso00")
        for ((mine, applied) in listOf(false to false, true to true)) {
            val added = MemoryRestore.add(MemoryFile(), listOf(item), emptyList(), mine, now = 2, people = { it }).file.items
            val replaced = MemoryRestore.replace(MemoryFile(), listOf(item), emptyList(), mine, people = { it }).file.items
            for (items in listOf(added, replaced)) {
                assertEquals(rule, items.single().filing)
                val rules = app.heyrobot.core.files.FilingRules.of(items)
                assertEquals(!applied, rules.single().tainted)
                assertEquals("mine=$mine", applied, app.heyrobot.core.files.FileRules.yours(rules, app.heyrobot.core.files.FileKind.RECEIPT, "Esso", null) != null)
            }
        }
    }

    private object NoPorts : app.heyrobot.core.files.FilingPorts by unused()
}

private fun unused(): app.heyrobot.core.files.FilingPorts = java.lang.reflect.Proxy.newProxyInstance(
    app.heyrobot.core.files.FilingPorts::class.java.classLoader, arrayOf(app.heyrobot.core.files.FilingPorts::class.java)) { _, _, _ -> error("unused") }
    as app.heyrobot.core.files.FilingPorts
