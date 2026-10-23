// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.Prepared
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.memory.MemoryCommand
import app.heyrobot.core.memory.MemoryHygiene
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryKind
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemoryRoute
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.memory.PromptPacking
import app.heyrobot.sys.FileMemoryVault
import app.heyrobot.sys.SoftMemoryKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The panel's memory (0.7, phase 7.1) on a real vault with a software key: the router's commands and what they say, the
 * cards and their Undo, "forget that", a pick card when several fit, the remember and forget tools (a secret refused
 * with its reason), what goes with a chat question, and what History keeps.
 */
class MemoryPanelTest {
    private val dir: File = Files.createTempDirectory("memory-panel").toFile()
    private val keys = SoftMemoryKeys()
    private val vault = FileMemoryVault(dir, keys)
    private var settings = MemorySettings()
    private var lockedNow = false
    private val now = LocalDate.of(2026, 10, 11).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() {
        MemoryPanel.vaultFor = { vault }
        MemoryPanel.settingsFor = { settings }
        MemoryPanel.clock = { now }
        MemoryPanel.zone = { ZoneOffset.UTC }
        MemoryPanel.lockedNow = { lockedNow }
        MemoryPanel.drop()
        MemoryPanel.newConversation()
    }

    @After fun tearDown() {
        MemoryPanel.drop()
        MemoryPanel.newConversation()
        dir.deleteRecursively()
    }

    private fun say(q: String, locked: Boolean = false, spoken: Boolean = true): MemoryAnswer {
        val r = MemoryPanel.route(q, locked) ?: throw AssertionError("not a memory command: $q")
        return MemoryPanel.command(r, locked, spoken)
    }

    private fun items(): List<MemoryItem> = (vault.load() as? MemoryLoad.Loaded)?.file?.items.orEmpty()

    @Test fun rememberSavesSaysItBackAndUndoes() {
        val a = say("Remember that my sister's birthday is May 3")
        assertEquals("Got it: your sister's birthday is May 3.", a.text)
        val saved = items().single()
        assertEquals("My sister's birthday is May 3", saved.text)
        assertEquals(MemorySource.SAID, saved.source)
        assertEquals(listOf(saved.id), a.ids)
        // Its card: no words in the summary (History keeps it), the words in the body, Undo and Open Memory.
        val card = a.card!!
        assertEquals(ToolName.REMEMBER, card.tool)
        assertEquals("Remembered", card.summary)
        assertEquals("My sister's birthday is May 3", card.body)
        assertEquals(SettingsPage.MEMORY, card.handOff)
        assertTrue(MemoryPanel.isUndo(card.undo))
        assertFalse(MemoryPanel.isUndo("8f0c2a3e-note-id"))
        assertEquals("Removed from memory.", MemoryPanel.undo(card.undo!!))
        assertTrue(items().isEmpty())
        assertEquals("Nothing to undo.", MemoryPanel.undo(card.undo!!))
        // Typed, it is "You typed it"; said again, Robot already remembers it.
        say("Remember that my sister's birthday is May 3", spoken = false)
        assertEquals(MemorySource.TYPED, items().single().source)
        assertEquals("I already remember that.", say("Remember that my sister's birthday is May 3").text)
    }

    @Test fun aPreferenceIsPinnedAndASecretStaysPrivate() {
        say("Remember that I prefer Celsius")
        val pref = items().single()
        assertEquals(MemoryKind.PREFERENCE, pref.kind)
        assertTrue(pref.pinned)
        val a = say("Remember that the door code is 4512")
        assertEquals("Got it: a code, kept in Memory.", a.text)
        assertEquals("[a code, kept in Memory]", a.card!!.body)
        assertTrue(items().single { it.id != pref.id }.secret)
        // History keeps the question without the code.
        val r = MemoryPanel.route("Remember that the door code is 4512", false)!!
        assertEquals("Remember: [a code, kept in Memory]", MemoryPanel.question(r, "Remember that the door code is 4512"))
        assertEquals("Remember that I prefer Celsius", MemoryPanel.question(MemoryPanel.route("Remember that I prefer Celsius", false)!!, "Remember that I prefer Celsius"))
    }

    @Test fun forgetThatTakesTheLastOneWithUndo() {
        assertEquals("There's nothing from this conversation to forget.", say("Forget that").text)
        say("Remember that Julie likes tulips")
        say("Remember that Léo has hockey on Tuesdays")
        val a = say("Forget that")
        assertEquals("Forgotten.", a.text)
        assertEquals(listOf("Julie likes tulips"), items().map { it.text })
        assertEquals("Forgotten", a.card!!.summary)
        assertEquals("• Léo has hockey on Tuesdays", a.card!!.body)
        assertEquals("Put back in memory.", MemoryPanel.undo(a.card!!.undo!!))
        assertEquals(2, items().size)
        assertEquals("C’est oublié.", say("Oublie ça").text)
    }

    @Test fun forgetSomethingSearchesAndAsksWhichWhenSeveralFit() {
        say("Remember that Julie likes tulips")
        say("Remember that Julie is vegetarian")
        say("Remember that Bob plays chess")
        assertEquals("I don't remember anything about that.", say("Forget the cottage").text)
        val one = say("Forget about chess")
        assertEquals("Forgotten.", one.text)
        assertEquals(2, items().size)
        val many = say("Forget Julie")
        assertEquals("Which one should I forget?", many.text)
        assertEquals(2, many.pick.size)
        assertEquals(listOf("Julie is vegetarian", "Julie likes tulips").sorted(), MemoryPanel.pickLabels(many.pick).sorted())
        assertEquals("Okay, I'll keep them.", MemoryPanel.forgetPicked(null, false).text)
        assertEquals(2, items().size)
        assertEquals("Forgotten.", MemoryPanel.forgetPicked(many.pick.first { it.text.contains("tulips") }, false).text)
        assertEquals(listOf("Julie is vegetarian"), items().map { it.text })
    }

    @Test fun recallAndAboutMeNeverSayASecret() {
        say("Remember that my sister Julie's birthday is May 3")
        say("Remember that Julie's door code is 4512")
        val r = say("What do you remember about Julie?")
        assertEquals("Here's what I remember about Julie: your sister Julie's birthday is May 3. And 1 private item, in Settings › Memory.", r.text)
        assertFalse(r.text.contains("4512"))
        val me = say("What do you know about me?")
        assertTrue(me.text, me.text.startsWith("I remember 2 things. For example: your sister Julie's birthday is May 3"))
        assertFalse(me.text.contains("4512"))
        assertEquals(SettingsPage.MEMORY, me.card!!.handOff)
        assertTrue(say("Qu'est-ce que tu sais sur moi?").text.startsWith("Je retiens 2 choses."))
        // "Forget everything": never by voice, a card to Settings › Memory.
        val all = say("Forget everything")
        assertEquals("Open Settings › Memory to delete everything.", all.text)
        assertEquals(ActionStatus.SKIPPED, all.card!!.status)
        assertEquals(2, items().size)
    }

    @Test fun lockedAndOff() {
        say("Remember that Julie likes tulips")
        keys.locked = true
        MemoryPanel.drop()
        assertEquals(MemoryRoute.Unlock("en"), MemoryPanel.route("What do you know about me?", locked = true))
        assertEquals("Unlock first: what you asked me to remember stays locked.", say("What do you know about me?", locked = true).text)
        assertEquals("Unlock first to save to memory.", say("Remember that Bob plays chess", locked = true).text)
        assertEquals(1, keys.keys.size)
        // A chat question on the locked phone gets no memory, and the file isn't even opened.
        assertEquals(MemoryTurn.NONE.packed, MemoryPanel.forTurn("When is Julie's birthday?", locked = true, given = emptySet(), maxTokens = 4096).packed)
        keys.locked = false
        settings = settings.copy(on = false)
        assertEquals("Memory is off. Turn it on in Robot's settings, under Memory.", say("Remember that Bob plays chess").text)
        assertFalse(MemoryPanel.forTurn("Julie?", false, emptySet(), 4096).tools)
        assertEquals(1, items().size)
    }

    /**
     * 1.0 audit (secrets-history-refused-remember): the question the panel saves for a refused "remember that …" (locked,
     * Memory off) is a placeholder: the PIN never reaches History's file.
     */
    @Test fun aRefusedRememberSavesAPlaceholder() {
        val pin = "remember that my bank PIN is 4821"
        keys.locked = true
        val locked = MemoryPanel.route(pin, locked = true)!!
        assertEquals("Unlock first to save to memory.", MemoryPanel.command(locked, true, spoken = true).text)
        assertEquals(MemoryHygiene.NOT_SAVED, MemoryPanel.question(locked, pin))
        keys.locked = false
        settings = settings.copy(on = false)
        val off = MemoryPanel.route(pin, locked = false)!!
        assertTrue("$off", off is MemoryRoute.Off)
        val saved = ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(ChatTurn("u", TurnRole.USER, MemoryPanel.question(off, pin), 1))))
        assertFalse(saved, saved.contains("4821"))
    }

    /**
     * 0.7 after the review: nothing is saved to or removed from memory on the lock screen, even with "Use memory on the
     * lock screen" on (which still lets it be read there): your "remember that …", the model's remember and forget, a
     * card answered after the phone locked, Undo, and the use counter.
     */
    @Test fun noMemoryWritesOnTheLockScreen() {
        settings = settings.copy(onLockScreen = true)
        say("Remember that Julie likes tulips")
        val before = items()
        lockedNow = true
        assertEquals(MemoryRoute.Unlock("en", saving = true), MemoryPanel.route("Remember that Bob plays chess", locked = true))
        assertEquals("Unlock first to save to memory.", say("Remember that Bob plays chess", locked = true).text)
        assertEquals("Déverrouille d’abord pour l’enregistrer en mémoire.", say("Souviens-toi que Bob joue aux échecs", locked = true).text)
        assertEquals("Unlock first to change what I remember.", say("Forget Julie", locked = true).text)
        assertEquals("Unlock first to change what I remember.", say("Forget that", locked = true).text)
        // Reading still works there with "Use memory on the lock screen".
        assertTrue(say("What do you remember about Julie?", locked = true).text.contains("tulips"))
        // The model's tools: refused with memory's line.
        val r = MemoryPanel.prepare(Action.Remember("Bob plays chess"), locked = true) as Prepared.Refused
        assertEquals(ActionStatus.NEEDS_UNLOCK, r.status)
        assertEquals("Unlock first to save to memory.", r.detail)
        assertEquals("Unlock first to change what I remember.", (MemoryPanel.prepare(Action.Forget("Julie"), locked = true) as Prepared.Refused).detail)
        // A card prepared unlocked and answered after the phone locked: nothing changes.
        assertEquals(ActionStatus.NEEDS_UNLOCK, MemoryPanel.run(Action.Remember("Bob plays chess"), spoken = true, content = false)!!.status)
        assertEquals(ActionStatus.NEEDS_UNLOCK, MemoryPanel.run(Action.Forget("Julie", before.map { it.id }), spoken = true, content = false)!!.status)
        MemoryPanel.used(before.map { it.id })
        assertEquals(before, items())
        lockedNow = false
        val saved = say("Remember that Bob plays chess")
        lockedNow = true
        assertEquals("Unlock first to change what I remember.", MemoryPanel.undo(saved.card!!.undo!!))
        assertEquals(2, items().size)
    }

    /**
     * 1.0.2, corrected in 1.1: the reply keeps every item the turn had, the pinned ones too (PanelController passes
     * MemoryTurn.recall.ids to MemoryPanel.mark), so a helper sees the conversation as personal; the question keeps only the
     * retrieved ones, whose notes come back in a rebuilt history.
     */
    @Test fun pinnedIdsGoOnTheReplyNotTheQuestion() {
        say("Remember that my sister Julie's birthday is May 3")
        say("Remember that I prefer Celsius")
        MemoryPanel.newConversation()
        val t = MemoryPanel.forTurn("When is Julie's birthday?", locked = false, given = emptySet(), maxTokens = 4096)
        val pref = items().first { it.pinned }
        val julie = items().first { it.text.contains("Julie") }
        assertEquals(listOf(pref.id), t.packed.pinnedIds)
        assertEquals(listOf(pref.id, julie.id), t.recall.ids)
        assertEquals(listOf(julie.id), t.packed.retrievedIds)
        MemoryPanel.mark("r-pinned", t.recall.ids)
        val reply = MemoryPanel.forHistory(app.heyrobot.core.ChatTurn("r-pinned", app.heyrobot.core.TurnRole.ASSISTANT, "May 3.", 1))
        assertEquals(listOf(pref.id, julie.id), reply.memoryIds)
    }

    @Test fun aChatQuestionGetsItsNotesAndHistoryKeepsTheIds() {
        say("Remember that my sister Julie's birthday is May 3")
        say("Remember that you should call me Sam")
        say("Remember that the cottage door code is 4512")
        MemoryPanel.newConversation()
        val t = MemoryPanel.forTurn("When is Julie's birthday?", locked = false, given = emptySet(), maxTokens = 4096)
        assertTrue(t.tools)
        val julie = items().first { it.text.contains("Julie") }
        assertEquals(listOf(julie.id), t.packed.retrievedIds)
        assertTrue(t.packed.notesBlock!!.startsWith(PromptPacking.NOTES_HEAD))
        assertFalse(t.quiet)
        // Rebuilt from History, a past question gets its notes again; a deleted item's are gone.
        assertTrue(t.notes(listOf(julie.id))!!.contains("birthday is May 3"))
        // "Forget that" now means the item that answer used.
        assertEquals("Forgotten.", say("Forget that").text)
        assertTrue(items().none { it.id == julie.id })
        // A question naming the secret gets it, shown but never spoken; History keeps a line instead of the answer.
        val code = MemoryPanel.forTurn("What's the cottage door code?", false, emptySet(), 4096)
        assertTrue(code.recall.secret && code.quiet)
        MemoryPanel.mark("r1", code.packed.retrievedIds, secret = true)
        val reply = MemoryPanel.forHistory(ChatTurn("r1", TurnRole.ASSISTANT, "The code is 4512.", 1))
        assertEquals(MemoryHygiene.SECRET_ANSWER, reply.text)
        assertEquals(code.packed.retrievedIds, reply.memoryIds)
        // Only once: a later reply with the same id is left alone.
        assertEquals("ok", MemoryPanel.forHistory(ChatTurn("r1", TurnRole.ASSISTANT, "ok", 2)).text)
        // With "Say private items aloud" on, named and unlocked, it may be spoken.
        settings = settings.copy(speakSecrets = true)
        assertFalse(MemoryPanel.forTurn("What's the cottage door code?", false, emptySet(), 4096).quiet)
        // The saved conversation holds ids, never the notes or the code.
        val q = ChatTurn("u1", TurnRole.USER, "What's the cottage door code?", 1, memoryIds = code.packed.retrievedIds)
        val json = ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(q, reply)))
        assertFalse(json.contains("4512"))
        assertTrue(json.contains(code.packed.retrievedIds.single()))
    }

    /**
     * A private item is never left in the live conversation (PanelController closes it after the answer, so a later answer
     * can't repeat it aloud or into History): asked for by name again in the same conversation, it is given again, as a
     * private answer again, instead of being taken as already there. An ordinary item already given isn't repeated.
     */
    @Test fun aPrivateItemIsGivenAgainWhenNamedAgain() {
        say("Remember that my sister Julie's birthday is May 3")
        say("Remember that the cottage door code is 4512")
        MemoryPanel.newConversation()
        val first = MemoryPanel.forTurn("What's the cottage door code?", false, emptySet(), 4096)
        assertTrue(first.recall.secret && first.quiet)
        val again = MemoryPanel.forTurn("And the cottage door code again?", false, first.packed.retrievedIds.toSet(), 4096)
        assertEquals(first.packed.retrievedIds, again.packed.retrievedIds)
        assertTrue(again.recall.secret && again.quiet)
        // Not named: not given.
        assertTrue(MemoryPanel.forTurn("What's the weather at the cottage?", false, first.packed.retrievedIds.toSet(), 4096).packed.retrievedIds.isEmpty())
        val julie = MemoryPanel.forTurn("When is Julie's birthday?", false, emptySet(), 4096).packed.retrievedIds
        assertEquals(1, julie.size)
        assertTrue(MemoryPanel.forTurn("When is Julie's birthday?", false, julie.toSet(), 4096).packed.retrievedIds.isEmpty())
    }

    /**
     * Settings › Memory › Delete all, while a "Forgotten" card's Undo still waits in the panel: the voice process hears it
     * (Cmd.MEMORY_CHANGED) and that Undo can't put the forgotten item back into the emptied memory.
     */
    @Test fun deleteAllLeavesNothingAnUndoCouldPutBack() {
        say("Remember that Julie likes tulips")
        val saved = say("Remember that Bob plays chess")
        val forgot = say("Forget about tulips")
        assertEquals("Forgotten.", forgot.text)
        assertTrue(vault.deleteAll())
        MemoryPanel.changedElsewhere()
        assertEquals("Nothing to undo.", MemoryPanel.undo(forgot.card!!.undo!!))
        assertTrue(items().isEmpty())
        // The Undo of what was saved only forgets it: nothing comes back either.
        MemoryPanel.undo(saved.card!!.undo!!)
        assertTrue(items().isEmpty())
    }

    @Test fun theToolsAreCardsAndRefuseASecret() {
        // The model proposing a code: refused, with the reason.
        val p = MemoryPanel.prepare(Action.Remember("The alarm code is 4512"), locked = false)
        assertTrue(p is Prepared.Refused && p.detail.contains("passwords, PINs, codes"))
        assertTrue(MemoryPanel.prepare(Action.Remember("Julie likes tulips"), false) is Prepared.Ready)
        assertTrue(MemoryPanel.prepare(Action.Remember("Julie likes tulips"), locked = true) is Prepared.Refused)
        assertNull(MemoryPanel.prepare(Action.SetTimer(60), false))
        // Run from a conversation with content: kept as content, where it came from said.
        val r = MemoryPanel.run(Action.Remember("Pick up Marc at 5"), spoken = true, content = true)!!
        assertEquals(ActionStatus.DONE, r.status)
        assertTrue(MemoryPanel.isUndo(r.undo))
        val kept = items().single()
        assertEquals(MemorySource.CONTENT, kept.source)
        assertTrue(kept.sourceLabel!!.isNotBlank())
        // forget: one match is ready (its card always shows: the gate), several are choices.
        MemoryPanel.run(Action.Remember("Julie likes tulips"), spoken = false, content = false)
        MemoryPanel.run(Action.Remember("Julie is vegetarian"), spoken = false, content = false)
        val one = MemoryPanel.prepare(Action.Forget("Marc"), false) as Prepared.Ready
        assertEquals(listOf(kept.id), (one.action as Action.Forget).ids)
        assertEquals("• Pick up Marc at 5", one.action.body())
        val two = MemoryPanel.prepare(Action.Forget("Julie"), false) as Prepared.Ready
        assertEquals(2, two.choices.size)
        assertTrue(MemoryPanel.prepare(Action.Forget("cottage"), false) is Prepared.Refused)
        val done = MemoryPanel.run(two.choices.first().action, spoken = false, content = false)!!
        assertEquals("Forgotten.", done.detail)
        assertEquals(2, items().size)
        assertEquals("Put back in memory.", MemoryPanel.undo(done.undo!!))
        assertEquals(3, items().size)
        assertNull(MemoryPanel.run(Action.SetTimer(60), false, false))
    }

    /**
     * 0.7, memory and messages: a reply History keeps as a message's placeholder keeps it through memory's record (its ids,
     * a private answer's line); your own "remember that …" kept after its card in a conversation that held a message is
     * content and says so; the card shows exactly the words kept; and notes kept from content make a turn content, also
     * when an earlier question of the conversation got them.
     */
    @Test fun withMessagesInTheConversation() {
        say("Remember that the cottage door code is 4512")
        val code = MemoryPanel.forTurn("What's the cottage door code?", false, emptySet(), 4096)
        MemoryPanel.mark("r1", code.packed.retrievedIds, secret = true)
        val placeholder = app.heyrobot.core.messages.MessageReading.ANSWER_NOT_KEPT
        val reply = MemoryPanel.forHistory(ChatTurn("r1", TurnRole.ASSISTANT, "The code is 4512, and Mom wrote: Bob is at 12 Elm Street", 1,
            messages = true, kept = placeholder))
        assertTrue(reply.messages)
        assertEquals(placeholder, reply.kept)
        assertEquals(code.packed.retrievedIds, reply.memoryIds)
        val json = ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(reply)))
        assertFalse(json.contains("4512") || json.contains("Elm") || json.contains(MemoryHygiene.SECRET_ANSWER))
        assertEquals(placeholder, ConversationCodec.fromJson(json)!!.turns.single().text)
        // The card's words are the words kept: cleaned before the card, never after.
        val ready = MemoryPanel.prepare(Action.Remember("  Mom's   flight lands at 6  "), locked = false) as Prepared.Ready
        assertEquals("Mom's flight lands at 6", ready.action.body())
        val r = MemoryPanel.run(ready.action, spoken = true, content = true, messages = true)!!
        assertEquals(ActionStatus.DONE, r.status)
        val kept = items().first { it.text == "Mom's flight lands at 6" }
        assertEquals(MemorySource.CONTENT, kept.source)
        assertEquals("your words in a conversation that held a message", kept.sourceLabel)
        // Notes from content make the turn content; your own words don't, and nor does an empty memory.
        MemoryPanel.newConversation()
        val own = MemoryPanel.forTurn("What's the cottage door code?", false, emptySet(), 4096)
        assertFalse(own.content)
        val flight = MemoryPanel.forTurn("When does Mom's flight land?", false, emptySet(), 4096)
        assertTrue(kept.id in flight.packed.retrievedIds)
        assertTrue(flight.content)
        // Given to an earlier question of this conversation (so not found again), it still makes the turn content.
        val later = MemoryPanel.forTurn("When does Mom's flight land?", false, setOf(kept.id), 4096)
        assertTrue(kept.id !in later.packed.retrievedIds)
        assertTrue(later.content)
    }

    @Test fun theCommandsAreTheRoutersOnly() {
        assertNull(MemoryPanel.route("Remember to buy milk", false))
        assertNull(MemoryPanel.route("When is Julie's birthday?", false))
        assertTrue((MemoryPanel.route("Souviens-toi que Julie aime les tulipes", false) as MemoryRoute.Run).command is MemoryCommand.Remember)
        assertEquals("C’est noté\u00A0: «\u00A0Julie aime les tulipes\u00A0».", say("Souviens-toi que Julie aime les tulipes").text)
    }
}
