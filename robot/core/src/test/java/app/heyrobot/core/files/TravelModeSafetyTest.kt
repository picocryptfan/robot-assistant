// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FX1 (files-1.4-review.md findings 2, 3, 5 and 6): travel mode fails closed when files_settings.json can't be read whole;
 * a save without your PIN never turns it off or unhides a drawer; a restore's finish merges over the file as it is then;
 * History shows Robot Files' answers as a placeholder while it hides drawers, and the stored turns stay as they were.
 */
class TravelModeSafetyTest {
    private var n = 0

    private fun entry(drawer: String?): FileEntry = FileEntry(FileId.of("%032x".format(++n))!!, "f$n", FileKind.RECEIPT,
        if (drawer == null) FileState.INBOX else FileState.FILED, Provenance(FileSource.SHARE, at = 1_000L * n, size = 10, sha256 = "a".repeat(64)), drawer = drawer)

    private val cabinet get() = FileIndex(listOf(entry(Drawers.MEDICAL), entry("Lawyer"), entry(Drawers.RECEIPTS), entry(null)),
        mapOf(FileViews.DRAWERS_KEY to listOf("Lawyer", "Car")))

    // Finding 5: a file that can't be read whole.

    @Test fun travelModesKeysAreReadOnTheirOwn() {
        // Another key damaged: travel mode as the file has it.
        val damaged = FilesSettingsCodec.decode("""{"v":1,"bills":"SOMETIMES","travel":true,"hidden":["Lawyer"]}""")
        assertTrue(damaged is FilesSettingsFile.Damaged)
        assertEquals(FilesSettings(travel = true, hidden = setOf("Lawyer")), damaged.settings)
        // A newer Robot's file: the same.
        val newer = FilesSettingsCodec.decode("""{"v":3,"travel":true,"hidden":["Lawyer"],"later":{}}""")
        assertTrue(newer is FilesSettingsFile.TooNew)
        assertEquals(FilesSettings(travel = true, hidden = setOf("Lawyer")), newer.settings)
        // Known off: off, whatever else (the hidden drawers kept when they can be read).
        assertEquals(FilesSettings(), FilesSettingsCodec.decode("""{"v":1,"bills":1,"hidden":[1]}""").settings)
        assertEquals(FilesSettings(hidden = setOf("Car")), FilesSettingsCodec.decode("""{"v":2,"hidden":["Car"]}""").settings)
        // On with no drawers named: nothing to hide.
        assertFalse(TravelMode.active(FilesSettingsCodec.decode("""{"v":1,"travel":"yes"}""").settings))
    }

    @Test fun nothingReadableFailsClosed() {
        for (bad in listOf("", "{", "not json", "[]", """{"v":1,"travel":true,"hidden":"Lawyer"}""", """{"v":1,"travel":"on","hidden":[7]}""",
            """{"v":9,"travel":true,"hidden":{"a":1}}""")) {
            val s = FilesSettingsCodec.decode(bad).settings
            assertEquals(bad, FilesSettings.CLOSED, s)
            assertTrue(bad, TravelMode.active(s) && TravelMode.hidesAny(s))
        }
        // Every drawer out of sight, starter or yours, and the files in them; Inbox stays.
        val v = TravelMode.shown(cabinet, FilesSettings.CLOSED)
        assertEquals(listOf(null), v.entries.map { it.drawer })
        assertEquals(emptyList<DrawerRow>(), FileViews.drawers(v))
        assertTrue(Drawers.STARTER.all { it in v.outOfSight } && "Lawyer" in v.outOfSight && "Car" in v.outOfSight)
        assertTrue(TravelMode.hidden("Anything", FilesSettings.CLOSED))
        assertTrue(TravelMode.hides(entry("Car"), FilesSettings.CLOSED))
        assertFalse(TravelMode.hidden(null, FilesSettings.CLOSED))
        // Your PIN shows them, as for any hidden drawer; off, nothing is hidden.
        assertTrue(TravelMode.shown(cabinet, FilesSettings.CLOSED, revealed = true).outOfSight.isEmpty())
        assertFalse(TravelMode.hidden("Car", FilesSettings.CLOSED, revealed = true))
        assertFalse(TravelMode.hidden("Car", FilesSettings.CLOSED.copy(travel = false)))
        // Never written: the flag is no key of the file.
        assertFalse(FilesSettingsCodec.encode(FilesSettings.CLOSED).contains("Unknown"))
    }

    // Findings 2 and 5: what a save writes.

    @Test fun aSaveWithoutThePinNeverTurnsTravelModeOff() {
        val on = FilesSettingsCodec.encode(FilesSettings(travel = true, hidden = setOf("Lawyer")))
        // An older copy of the page (travel off, nothing hidden) changing another row: travel mode stays, and its drawer.
        val stale = FilesSettingsWrite.save(on, FilesSettings(brief = false), pin = false)!!
        assertEquals(FilesSettings(brief = false, travel = true, hidden = setOf("Lawyer")), FilesSettingsCodec.decode(stale).settings)
        // Hiding one more needs nothing.
        assertEquals(setOf("Lawyer", "Car"), FilesSettingsCodec.decode(FilesSettingsWrite.save(on, FilesSettings(travel = true, hidden = setOf("Lawyer", "Car")), pin = false)).settings.hidden)
        // With your PIN: off, or a drawer unhidden.
        assertEquals(FilesSettings(hidden = setOf("Lawyer")), FilesSettingsCodec.decode(FilesSettingsWrite.save(on, FilesSettings(hidden = setOf("Lawyer")), pin = true)).settings)
        assertEquals(FilesSettings(travel = true), FilesSettingsCodec.decode(FilesSettingsWrite.save(on, FilesSettings(travel = true), pin = true)).settings)
        // Travel mode off in the file: anything goes, as before.
        assertEquals(FilesSettings(bills = FileLevel.ASK), FilesSettingsCodec.decode(FilesSettingsWrite.save(null, FilesSettings(bills = FileLevel.ASK), pin = false)).settings)
    }

    @Test fun aClosedFileIsOnlyWrittenByTurningTravelModeOffWithThePin() {
        for (raw in listOf("{", """{"v":4,"travel":true,"hidden":"?"}""")) {
            val now = FilesSettingsCodec.decode(raw).settings
            assertEquals(FilesSettings.CLOSED, now)
            // Any other change, even with the PIN, can't be written: which drawers are hidden isn't known.
            assertNull(raw, FilesSettingsWrite.save(raw, now.copy(brief = false), pin = false))
            assertNull(raw, FilesSettingsWrite.save(raw, now.copy(brief = false), pin = true))
            assertNull(raw, FilesSettingsWrite.save(raw, now.copy(travel = false), pin = false))
            // Off with your PIN.
            val off = FilesSettingsWrite.save(raw, now.copy(travel = false), pin = true)!!
            assertFalse(raw, TravelMode.active(FilesSettingsCodec.decode(off).settings))
        }
        // A damaged file is written new; a newer Robot's keeps everything but travel mode's key.
        assertEquals(FilesSettingsFile.Ok(FilesSettings()), FilesSettingsCodec.decode(FilesSettingsWrite.save("{", FilesSettings.CLOSED.copy(travel = false), pin = true)))
        val o = JSONObject(FilesSettingsWrite.save("""{"v":4,"travel":true,"hidden":"?","later":[1]}""", FilesSettings.CLOSED.copy(travel = false), pin = true)!!)
        assertEquals(4, o.getInt("v"))
        assertFalse(o.has("travel"))
        assertEquals("?", o.getString("hidden"))
        assertEquals(1, o.getJSONArray("later").getInt(0))
    }

    @Test fun aNewerFileChangesOnlyTravelMode() {
        val raw = """{"v":2,"on":false,"travel":true,"hidden":["Lawyer"],"later":7}"""
        val now = FilesSettingsCodec.decode(raw).settings
        // Off with your PIN: only that key goes.
        val off = JSONObject(FilesSettingsWrite.save(raw, now.copy(travel = false), pin = true)!!)
        assertEquals(2, off.getInt("v"))
        assertFalse(off.getBoolean("on"))
        assertFalse(off.has("travel"))
        assertEquals("Lawyer", off.getJSONArray("hidden").getString(0))
        assertEquals(7, off.getInt("later"))
        // One more drawer hidden: allowed.
        assertEquals(listOf("Car", "Lawyer"), JSONObject(FilesSettingsWrite.save(raw, now.copy(hidden = setOf("Lawyer", "Car")), pin = false)!!)
            .getJSONArray("hidden").let { a -> (0 until a.length()).map { a.getString(it) } })
        // Anything else stays the newer Robot's.
        assertNull(FilesSettingsWrite.save(raw, now.copy(brief = false), pin = false))
        assertNull(FilesSettingsWrite.save(raw, now.copy(travel = false, brief = false), pin = true))
    }

    // Finding 2: a restore's finish.

    @Test fun aRestoresFinishKeepsWhatChangedSinceStaging() {
        // Staged: the backup's own file, travel mode off, a looser level.
        val staged = FilesSettingsCodec.encode(FilesSettings(bills = FileLevel.ASK, brief = false))
        // Since then, travel mode turned on with a drawer hidden.
        val here = FilesSettingsCodec.encode(FilesSettings(travel = true, hidden = setOf("Lawyer")))
        for (mine in listOf(true, false)) {
            val out = FilesSettingsCodec.decode(RestoreSafety.filesSettingsFile(here, staged, mine)).settings
            assertTrue(out.travel)
            assertEquals(setOf("Lawyer"), out.hidden)
            assertEquals(FileLevel.ASK, out.bills)
            assertFalse(out.brief)
        }
        // Unhidden with the PIN since staging, from your own backup that hid nothing: stays unhidden.
        assertEquals(emptySet<String>(), FilesSettingsCodec.decode(RestoreSafety.filesSettingsFile(FilesSettingsCodec.encode(FilesSettings()), staged, true)).settings.hidden)
        // Nothing written over a newer Robot's file, a closed one, or from a staged file that can't be read.
        assertNull(RestoreSafety.filesSettingsFile("""{"v":2}""", staged, true))
        assertNull(RestoreSafety.filesSettingsFile("{", staged, true))
        assertNull(RestoreSafety.filesSettingsFile(here, "{", true))
        assertNull(RestoreSafety.filesSettingsFile(here, null, true))
        // No file here yet: the backup's.
        assertEquals(FilesSettings(bills = FileLevel.ASK, brief = false), FilesSettingsCodec.decode(RestoreSafety.filesSettingsFile(null, staged, true)).settings)
    }

    // Finding 3: History.

    private val en = Langs.of(Lang.EN)
    private val placeholder = FilesStrings.travelAnswer().render(en)
    private val question = FilesStrings.travelQuestion().render(en)

    private fun conversation() = StoredConversation("c1", 1, 9, listOf(
        ChatTurn("u1", TurnRole.USER, "How much did I spend at the pharmacy?", 1),
        ChatTurn("r1", TurnRole.ASSISTANT, "You spent $84.12 at Jean Coutu.", 2, files = true),
        ChatTurn("u2", TurnRole.USER, "Find my passport", 3),
        ChatTurn("r2", TurnRole.ASSISTANT, "1. Passport scan, in Lawyer.", 4, actions = listOf(
            ActionRecord(ToolName.FIND_FILES, "Found 1 file: Passport scan", ActionStatus.DONE, "Lawyer"),
            ActionRecord(ToolName.SET_TIMER, "Timer for 5 minutes", ActionStatus.DONE))),
        ChatTurn("u3", TurnRole.USER, "What's its number?", 5),
        ChatTurn("r3", TurnRole.ASSISTANT, "AB123456.", 6, actions = listOf(ActionRecord(ToolName.READ_FILE, "Passport scan", ActionStatus.DONE, content = true))),
        ChatTurn("u4", TurnRole.USER, "Thanks", 7),
        ChatTurn("r4", TurnRole.ASSISTANT, "You're welcome.", 8),
    ))

    /**
     * FX3, deliberately (FX1 masked only the answers): a conversation holding Robot Files' answer shows whole as
     * placeholders, your questions too (they name drawers and files, and the first one is the title), with no card of any
     * tool (add_event's names the file's title too).
     */
    @Test fun historyShowsTheWholeConversationAsPlaceholders() {
        val c = conversation()
        val shown = TravelMode.history(c, placeholder, question)
        assertEquals(listOf("r1", "r2", "r3"), c.turns.filter(TravelMode::fromFiles).map { it.id })
        assertEquals(c.turns.map { it.id }, shown.turns.map { it.id })
        assertEquals(c.turns.map { if (it.role == TurnRole.USER) question else placeholder }, shown.turns.map { it.text })
        assertEquals(emptyList<ToolName>(), shown.turns.flatMap { it.actions }.map { it.tool })
        assertEquals(question, shown.title)
        val all = shown.title + shown.turns.joinToString { it.text + it.actions.joinToString { a -> a.summary + a.detail } }
        assertFalse(all.contains("Jean Coutu") || all.contains("AB123456") || all.contains("Lawyer") || all.contains("Passport") || all.contains("pharmacy"))
        // Nothing of Files': the same conversation.
        val plain = StoredConversation("c2", 1, 2, listOf(ChatTurn("u", TurnRole.USER, "Hi", 1), ChatTurn("r", TurnRole.ASSISTANT, "Hello.", 2)))
        assertTrue(TravelMode.history(plain, placeholder, question) === plain)
        assertEquals("[An answer from Robot Files, out of sight while travel mode is on.]", placeholder)
        assertEquals("[Out of sight while travel mode is on.]", question)
    }

    /**
     * FX3: the router's replies saved before FX1 carry no mark: found by their question (FilesRouter takes it), in English
     * and French; a picture or the screen's text on your turn goes too. A question Files' router doesn't take changes nothing.
     */
    @Test fun unmarkedRouterRepliesAreFoundByTheirQuestion() {
        for (q in listOf("where's my lease", "find my Costco receipt from August", "combien j'ai mis chez Costco le mois passé", "move it to Taxes")) {
            val c = StoredConversation("c", 1, 2, listOf(ChatTurn("u", TurnRole.USER, q, 1, screenText = "Lawyer: AB123456"),
                ChatTurn("r", TurnRole.ASSISTANT, "You spent $312.40 at Costco.", 2)))
            assertTrue(q, TravelMode.holdsFiles(c.turns))
            val shown = TravelMode.history(c, placeholder, question)
            assertEquals(q, listOf(question, placeholder), shown.turns.map { it.text })
            assertEquals(q, listOf(null, null), shown.turns.map { it.screenText })
        }
        val weather = StoredConversation("w", 1, 2, listOf(ChatTurn("u", TurnRole.USER, "What's the weather tomorrow?", 1),
            ChatTurn("r", TurnRole.ASSISTANT, "Sunny, 12 degrees.", 2)))
        assertTrue(TravelMode.history(weather, placeholder, question) === weather)
    }

    /** FX3: a reminder card's Add to calendar: its reply is marked (PanelController.filesCalendar), and its add_event card goes. */
    @Test fun aCalendarReplyFromAFileIsMasked() {
        val c = StoredConversation("c", 1, 2, listOf(ChatTurn("u", TurnRole.USER, "Add to calendar", 1),
            ChatTurn("r", TurnRole.ASSISTANT, "Here's the event.", 2, files = true, actions = listOf(
                ActionRecord(ToolName.ADD_EVENT, "Hydro bill due", ActionStatus.DONE, "Nov 3")))))
        val shown = TravelMode.history(c, placeholder, question)
        assertEquals(emptyList<ActionRecord>(), shown.turns[1].actions)
        assertFalse(shown.toString().contains("Hydro"))
    }

    @Test fun theListsPreviewShowsThePlaceholderToo() {
        val c = conversation().let { it.copy(turns = it.turns.take(2)) }
        assertTrue(c.preview.contains("Jean Coutu"))
        assertEquals(placeholder, TravelMode.history(c, placeholder, question).preview)
    }

    @Test fun theRoutersMarkIsSavedAndReadBack() {
        val c = conversation()
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(c))!!
        assertEquals(listOf(true, false), back.turns.filter { it.id == "r1" || it.id == "r4" }.map { it.files })
        assertFalse(JSONObject(ConversationCodec.toJson(c.copy(turns = c.turns.drop(2)))).toString().contains("\"files\""))
    }

    @Test fun aContinuedConversationIsSavedWithItsStoredTurns() {
        val c = conversation()
        val opened = TravelMode.continued(c, hiding = true, placeholder, question)
        assertEquals(TravelMode.history(c, placeholder, question), opened.shown)
        // Turns added after it and the shown placeholders: saved with every turn as it was stored (FX3: your questions too).
        val next = opened.shown.turns + ChatTurn("u5", TurnRole.USER, "Bye", 10) + ChatTurn("r5", TurnRole.ASSISTANT, "Bye.", 11)
        val saved = opened.saved(next)
        assertEquals(c.turns + next.takeLast(2), saved)
        // Travel mode off: shown as stored, nothing to put back.
        val plain = TravelMode.continued(c, hiding = false, placeholder, question)
        assertTrue(plain.shown === c)
        assertEquals(c.turns, plain.saved(c.turns))
    }

    @Test fun thePlaceholderSpeaksBothLanguages() {
        assertEquals("[Masqué pendant que le mode voyage est activé.]", FilesStrings.travelQuestion().render(Langs.of(Lang.FR)))
        assertEquals("[Une réponse des Fichiers de Robot, masquée pendant que le mode voyage est activé.]",
            FilesStrings.travelAnswer().render(Langs.of(Lang.FR)).replace(' ', ' ').replace(' ', ' '))
    }

    /**
     * FX3: a change in Settings › Files is applied to the file as it is when saved, so what a restore's finish wrote after
     * the page read the file stays: without the PIN and with it (turning travel mode off writes only that).
     */
    @Test fun aChangeKeepsWhatAnotherWriterWroteMeanwhile() {
        // The page read the defaults; a restore's finish then wrote a filing level, clean-up off and travel mode on.
        val restored = FilesSettingsCodec.encode(FilesSettings(receipts = FileLevel.ASK, cleanup = false, travel = true, hidden = setOf("Lawyer")))
        val brief = FilesSettingsWrite.update(restored, pin = false) { it.copy(brief = false) }!!
        assertEquals(FilesSettings(receipts = FileLevel.ASK, cleanup = false, brief = false, travel = true, hidden = setOf("Lawyer")),
            FilesSettingsCodec.decode(brief).settings)
        val off = FilesSettingsWrite.update(brief, pin = true) { it.copy(travel = false) }!!
        assertEquals(FilesSettings(receipts = FileLevel.ASK, cleanup = false, brief = false, hidden = setOf("Lawyer")), FilesSettingsCodec.decode(off).settings)
        // Without the PIN a change still can't turn travel mode off or unhide a drawer.
        val tried = FilesSettingsWrite.update(restored, pin = false) { it.copy(travel = false, hidden = emptySet()) }!!
        assertEquals(FilesSettings(receipts = FileLevel.ASK, cleanup = false, travel = true, hidden = setOf("Lawyer")), FilesSettingsCodec.decode(tried).settings)
        // Keys this version doesn't know stay.
        assertEquals(1, JSONObject(FilesSettingsWrite.update("""{"v":1,"later":1}""", pin = false) { it.copy(brief = false) }!!).getInt("later"))
    }

    /**
     * FX3: a hidden drawer renamed stays hidden under its new name in the old one's place, with no PIN, even with every
     * place taken (MAX_HIDDEN): a save without the PIN would have kept the old name and had no room for the new one.
     */
    @Test fun aRenamedHiddenDrawerKeepsItsPlace() {
        val full = (1..FilesSettings.MAX_HIDDEN).map { "Drawer $it" }.toSet()
        val raw = FilesSettingsCodec.encode(FilesSettings(travel = true, hidden = full))
        val renamed = FilesSettingsCodec.decode(FilesSettingsWrite.rename(raw, "Drawer 7", "Lawyer")).settings
        assertTrue(renamed.travel)
        assertEquals(full - "Drawer 7" + "Lawyer", renamed.hidden)
        // The old way (the renamed copy saved without the PIN) would have lost it: kept() puts the old name back first.
        assertFalse("Lawyer" in FilesSettingsCodec.decode(FilesSettingsWrite.save(raw, TravelMode.renamed(FilesSettings(travel = true, hidden = full),
            "Drawer 7", "Lawyer"), pin = false)).settings.hidden)
        // A drawer that isn't hidden, or a name that can't be one: nothing written.
        assertNull(FilesSettingsWrite.rename(raw, "Car", "Auto"))
        assertNull(FilesSettingsWrite.rename(raw, "Drawer 7", ""))
        // Travel mode on with its drawers unknown (a damaged file): nothing can be written.
        assertNull(FilesSettingsWrite.rename("{", "Drawer 7", "Lawyer"))
        // A newer Robot's file: only "hidden" changes.
        val newer = FilesSettingsWrite.rename("""{"v":2,"travel":true,"hidden":["Car"],"later":1}""", "Car", "Auto")!!
        assertEquals(listOf("Auto"), JSONObject(newer).getJSONArray("hidden").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(1, JSONObject(newer).getInt("later"))
        assertEquals(2, JSONObject(newer).getInt("v"))
    }
}
