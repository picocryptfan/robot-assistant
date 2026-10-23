// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.Action
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.HelperContext
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.claudeSettings
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.GateDecision
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Personal
import app.heyrobot.core.PromptRole
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TurnRole
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.toPromptTurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 0.7 phase 7.1, the panel's side as pure code: the router before the model, what Robot says, what History keeps, what
 * goes with a question (and to a helper), the tools and their gate, and memory's own settings file.
 */
class MemoryWiringTest {
    private val utc = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 3, 12).atStartOfDay(utc).toInstant().toEpochMilli()
    private val now = LocalDate.of(2026, 10, 11).atStartOfDay(utc).toInstant().toEpochMilli()
    private val on = MemorySettings()

    private fun item(id: String, text: String, kind: MemoryKind = MemoryKind.FACT, pinned: Boolean = false, secret: Boolean = false,
        uses: Int = 0, source: MemorySource = MemorySource.SAID, expires: Long? = null) =
        MemoryItem(id, text, kind, secret = secret, pinned = pinned, source = source, createdAt = day, uses = uses, expiresAt = expires)

    private val sam = item("m_pref00000001", "Call me Sam", MemoryKind.PREFERENCE, pinned = true)
    private val julie = item("m_fact00000001", "My sister Julie's birthday is May 3", uses = 4)
    private val hockey = item("m_fact00000002", "Léo has hockey on Tuesdays and Thursdays", uses = 1)
    private val door = item("m_secr00000001", "The door code at the cottage is 4512", secret = true)
    private val file = MemoryFile(listOf(sam, julie, hockey, door))

    // ------------------------------------------------------------------ the router

    @Test fun theRouterTakesMemoryCommandsBeforeTheModel() {
        val r = MemoryRouting.route("Remember that my sister's birthday is May 3", on, locked = false)
        assertTrue(r is MemoryRoute.Run && r.command is MemoryCommand.Remember)
        assertTrue((MemoryRouting.route("Souviens-toi que la fête de ma sœur est le 3 mai", on, false) as MemoryRoute.Run).command.language == "fr")
        assertTrue((MemoryRouting.route("Forget that", on, false) as MemoryRoute.Run).command is MemoryCommand.ForgetLast)
        assertTrue((MemoryRouting.route("oublie ça", on, false) as MemoryRoute.Run).command is MemoryCommand.ForgetLast)
        assertTrue((MemoryRouting.route("forget my sister's birthday", on, false) as MemoryRoute.Run).command is MemoryCommand.Forget)
        assertTrue((MemoryRouting.route("What do you remember about Julie?", on, false) as MemoryRoute.Run).command is MemoryCommand.Recall)
        assertTrue((MemoryRouting.route("What do you know about me?", on, false) as MemoryRoute.Run).command is MemoryCommand.AboutMe)
        // A note stays a note, and a plain question goes to the model.
        assertNull(MemoryRouting.route("Remember to buy milk", on, false))
        assertNull(MemoryRouting.route("N'oublie pas d'acheter du lait", on, false))
        assertNull(MemoryRouting.route("When is my sister's birthday?", on, false))
    }

    @Test fun lockedOffAndForgetEverything() {
        // Locked, memory not on the lock screen: commands and memory questions hear "Unlock first"; others go on without memory.
        assertEquals(MemoryRoute.Unlock("en", saving = true), MemoryRouting.route("Remember that I parked on P3", on, locked = true))
        assertEquals(MemoryRoute.Unlock("en"), MemoryRouting.route("What do you know about me?", on, locked = true))
        assertEquals(MemoryRoute.Unlock("en"), MemoryRouting.route("Do you remember when Julie's birthday is?", on, locked = true))
        assertEquals(MemoryRoute.Unlock("fr"), MemoryRouting.route("Tu te souviens de la fête de Julie?", on, locked = true))
        assertNull(MemoryRouting.route("When is Julie's birthday?", on, locked = true))
        // With "Use memory on the lock screen" what reads runs; nothing is saved or removed there, ever.
        assertTrue(MemoryRouting.route("What do you know about me?", on.copy(onLockScreen = true), locked = true) is MemoryRoute.Run)
        assertEquals(MemoryRoute.Unlock("en", saving = true), MemoryRouting.route("Remember that I parked on P3", on.copy(onLockScreen = true), locked = true))
        assertEquals(MemoryRoute.Unlock("fr", saving = true), MemoryRouting.route("N'oublie pas que Marc vient samedi", on.copy(onLockScreen = true), locked = true))
        assertEquals(MemoryRoute.Unlock("en", changing = true), MemoryRouting.route("Forget my sister's birthday", on.copy(onLockScreen = true), locked = true))
        assertEquals(MemoryRoute.Unlock("fr", changing = true), MemoryRouting.route("Oublie ça", on.copy(onLockScreen = true), locked = true))
        assertEquals("Unlock first to save to memory.", MemoryReplies.unlock(false, saving = true))
        assertEquals("Déverrouille d’abord pour changer ce que je retiens.", MemoryReplies.unlock(true, changing = true))
        // The model's remember and forget: never on the lock screen, whatever "Works on the lock screen" holds.
        val lockedAll = app.heyrobot.core.RobotSettings().let { it.copy(lockedTools = it.lockedTools + app.heyrobot.core.ToolName.REMEMBER + app.heyrobot.core.ToolName.FORGET) }
        for (t in listOf(app.heyrobot.core.ToolName.REMEMBER, app.heyrobot.core.ToolName.FORGET)) {
            assertEquals(app.heyrobot.core.GateDecision.NEEDS_UNLOCK, app.heyrobot.core.ApprovalGate.decide(t, locked = true, settings = lockedAll, asked = true))
            assertTrue(app.heyrobot.core.ApprovalGate.lockedOut(t, true, lockedAll))
        }
        assertEquals("Unlock first to save to memory.", app.heyrobot.core.ApprovalGate.unlockLine(app.heyrobot.core.ToolName.REMEMBER))
        // Off: a command is answered, nothing kept, nothing to the model; anything else is ordinary talk.
        assertEquals(MemoryRoute.Off("en"), MemoryRouting.route("Remember that my sister's birthday is May 3", on.copy(on = false), false))
        assertNull(MemoryRouting.route("Do you remember the capital of Peru?", on.copy(on = false), true))
        // "Forget everything" is never done by voice, locked or not.
        val all = MemoryRouting.route("Forget everything", on, locked = true)
        assertTrue(all is MemoryRoute.Run && all.command is MemoryCommand.ForgetEverything)
        assertEquals("Open Settings › Memory to delete everything.", MemoryReplies.forgetAll(fr = false))
        assertEquals("Ouvre Réglages › Mémoire pour tout effacer.", MemoryReplies.forgetAll(fr = true))
    }

    // ------------------------------------------------------------------ what Robot says

    @Test fun theRepliesSayItBackWithoutSecrets() {
        val c = MemoryCommands.parse("Remember that my sister's birthday is May 3") as MemoryCommand.Remember
        assertEquals("Got it: your sister's birthday is May 3.", MemoryReplies.saved(c, AddOutcome.SAVED))
        assertEquals("I already remember that.", MemoryReplies.saved(c, AddOutcome.ALREADY_KEPT))
        assertTrue(MemoryReplies.saved(c, AddOutcome.FULL).contains("Settings › Memory"))
        assertTrue(MemoryReplies.saved(c, AddOutcome.SAVED_PIN_FULL).endsWith(MemoryReplies.PIN_FULL))
        val code = MemoryCommands.parse("Remember that the door code is 4512") as MemoryCommand.Remember
        assertTrue(code.secret)
        assertEquals("Got it: a code, kept in Memory.", MemoryReplies.saved(code, AddOutcome.SAVED))
        val fr = MemoryCommands.parse("Souviens-toi que la fête de ma sœur est le 3 mai") as MemoryCommand.Remember
        assertEquals("C’est noté\u00A0: «\u00A0La fête de ma sœur est le 3 mai\u00A0».", MemoryReplies.saved(fr, AddOutcome.SAVED))
        assertEquals("Forgotten.", MemoryReplies.forgotten(1, false))
        assertEquals("C’est oublié\u00A0: 2 choses.", MemoryReplies.forgotten(2, true))
        // A preference that tells Robot what to do isn't turned to "you".
        assertEquals("Got it: I'll call you Sam.", MemoryReplies.saved(MemoryCommands.parse("Remember: call me Sam") as MemoryCommand.Remember, AddOutcome.SAVED))
        assertEquals("Got it: “Always answer briefly”.", MemoryReplies.saved(MemoryCommands.parse("Remember: always answer briefly") as MemoryCommand.Remember, AddOutcome.SAVED))
        assertEquals("Got it: you prefer Celsius.", MemoryReplies.saved(MemoryCommands.parse("Remember that I prefer Celsius") as MemoryCommand.Remember, AddOutcome.SAVED))
        assertEquals("Unlock first to save to memory.", MemoryReplies.unlock(false, saving = true))
        assertEquals(MemoryAccess.UNLOCK_FIRST, MemoryReplies.unlock(false))
        assertTrue(MemoryReplies.off(false).contains("under Memory"))
    }

    @Test fun recallAndAboutMeReadItemsBackAsYou() {
        assertEquals("Here's what I remember about Julie: your sister Julie's birthday is May 3.",
            MemoryReplies.recall("Julie", listOf(julie), private = 0, fr = false))
        assertEquals("I don't remember anything about Bob.", MemoryReplies.recall("Bob", emptyList(), 0, false))
        assertEquals("I don't remember anything about the cottage. And 1 private item, in Settings › Memory.",
            MemoryReplies.recall("the cottage", emptyList(), private = 1, fr = false))
        assertEquals("Voici ce que je retiens sur Julie\u00A0: «\u00A0My sister Julie's birthday is May 3\u00A0».", MemoryReplies.recall("Julie", listOf(julie), 0, true))
        val ex = MemorySearch.examples(file.items, now)
        assertEquals(listOf(julie, hockey, sam), ex)
        assertTrue(door !in MemorySearch.examples(file.items, now, n = 10))
        assertEquals("I remember 4 things. For example: your sister Julie's birthday is May 3; Léo has hockey on Tuesdays and Thursdays; “Call me Sam”.",
            MemoryReplies.aboutMe(4, ex, false))
        assertTrue(MemoryReplies.aboutMe(0, emptyList(), false).startsWith("I don't remember anything yet."))
        assertTrue(MemoryReplies.aboutMe(2, emptyList(), true).startsWith("Je retiens 2 choses."))
    }

    @Test fun searchesForForgetAndRecall() {
        assertEquals(listOf(julie), MemorySearch.find("Julie", file.items, now))
        assertEquals(listOf(julie), MemorySearch.clear("my sister's birthday", file.items, now))
        assertEquals(listOf(hockey), MemorySearch.find("hockey", file.items, now))
        assertEquals(emptyList<MemoryItem>(), MemorySearch.find("the", file.items, now))
        // Two that fit about as well: a pick card.
        val other = item("m_fact00000003", "Julie is vegetarian")
        assertEquals(2, MemorySearch.clear("Julie", file.items + other, now).size)
        // An item that ended is gone for searches too.
        val parked = item("m_fact00000004", "I parked on P3", expires = now - 1)
        assertEquals(emptyList<MemoryItem>(), MemorySearch.find("parked", listOf(parked), now))
        // "Forget that": the item saved last, unless an answer used memory after it.
        assertEquals(listOf("m_a"), ForgetLast.target("m_a", 20, listOf("m_b"), 10))
        assertEquals(listOf("m_b", "m_c"), ForgetLast.target("m_a", 5, listOf("m_b", "m_c"), 10))
        assertEquals(emptyList<String>(), ForgetLast.target(null, 0, emptyList(), 0))
    }

    // ------------------------------------------------------------------ History hygiene

    /**
     * 1.0 audit (secrets-history-refused-remember): "remember that my bank PIN is 4821" on a locked phone, or with Memory
     * off, is refused, and History keeps a placeholder, never the words; another refused command loses a code it holds.
     */
    @Test fun aRefusedRememberIsNeverKeptInHistory() {
        val pin = "hey robot, remember that my bank PIN is 4821"
        for (route in listOf(MemoryRouting.route(pin, MemorySettings(on = true), locked = true)!!, MemoryRouting.route(pin, MemorySettings(on = false), locked = false)!!)) {
            assertTrue("$route", route !is MemoryRoute.Run)
            val kept = MemoryHygiene.question(route, pin)
            assertEquals(MemoryHygiene.NOT_SAVED, kept)
            val json = ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(ChatTurn("u", TurnRole.USER, kept, 1))))
            assertFalse(json, json.contains("4821") || json.contains("PIN"))
        }
        // Not a secret, still not kept: it wasn't saved.
        assertEquals(MemoryHygiene.NOT_SAVED, MemoryHygiene.question(MemoryRoute.Unlock("en", saving = true), "remember that I parked on level 3"))
        assertEquals(MemoryHygiene.NOT_SAVED_FR, MemoryHygiene.question(MemoryRoute.Off("fr"), "retiens que mon NIP est 4821"))
        // Another refused command with a code in it.
        assertEquals(MemoryHygiene.PRIVATE, MemoryHygiene.question(MemoryRoute.Unlock("en", changing = true), "forget the garage code 4512"))
        assertEquals("what do you know about me?", MemoryHygiene.question(MemoryRoute.Unlock("en"), "what do you know about me?"))
        // Run: as before.
        val c = MemoryCommands.parse("Remember that the door code is 4512") as MemoryCommand.Remember
        assertEquals("Remember: [a code, kept in Memory]", MemoryHygiene.question(MemoryRoute.Run(c), "Remember that the door code is 4512"))
    }

    @Test fun historyKeepsIdsNotTheNotes() {
        val c = MemoryCommands.parse("Remember that the door code is 4512") as MemoryCommand.Remember
        assertEquals("Remember: [a code, kept in Memory]", MemoryHygiene.question(c, "Remember that the door code is 4512"))
        val plain = MemoryCommands.parse("Remember that my sister's birthday is May 3") as MemoryCommand.Remember
        assertEquals("Remember that my sister's birthday is May 3", MemoryHygiene.question(plain, "Remember that my sister's birthday is May 3"))
        assertEquals("[a code, kept in Memory]", MemoryHygiene.shown(door))
        // A saved conversation holds the ids, never the notes; a bad id is dropped on reading.
        val q = ChatTurn("u1", TurnRole.USER, "When is my sister's birthday?", 1, memoryIds = listOf(julie.id, sam.id))
        val conv = StoredConversation("c1", 1, 2, listOf(q, ChatTurn("r1", TurnRole.ASSISTANT, "May 3.", 2)))
        val json = ConversationCodec.toJson(conv)
        assertFalse(json.contains("birthday is May 3"))
        assertTrue(json.contains(julie.id))
        assertEquals(listOf(julie.id, sam.id), ConversationCodec.fromJson(json)!!.turns[0].memoryIds)
        assertEquals(emptyList<String>(), ConversationCodec.fromJson(json.replace(julie.id, "../evil").replace(sam.id, "x"))!!.turns[0].memoryIds)
        // Rebuilt, the question gets its notes again from memory as it is now: none for a deleted or secret item.
        val turns = conv.turns.toPromptTurns { ids -> PromptPacking.notesFor(ids, file.items, now, utc) }
        assertEquals(PromptRole.USER, turns[0].role)
        assertTrue(turns[0].text.startsWith(PromptPacking.NOTES_HEAD))
        assertTrue(turns[0].text.contains("My sister Julie's birthday is May 3.") && turns[0].text.endsWith("When is my sister's birthday?"))
        assertNull(PromptPacking.notesFor(listOf(door.id, "m_gone000000"), file.items, now, utc))
        assertEquals("When is my sister's birthday?", conv.turns.toPromptTurns()[0].text)
    }

    // ------------------------------------------------------------------ helpers

    @Test fun memoryReachesAHelperOnlyThroughTheCard() {
        val turns = listOf(ChatTurn("u1", TurnRole.USER, "When is my sister's birthday?", 1, memoryIds = listOf(julie.id)),
            ChatTurn("r1", TurnRole.ASSISTANT, "May 3.", 2))
        val p = HelperContext.personal(turns)
        assertEquals(setOf(Personal.MEMORY), p)
        assertEquals("Include the conversation so far? It contains what Robot remembers about you.", HelperContext.includeQuestion(turns))
        val s = RobotSettings()
        val trusted = HelperConfig.claude().copy(trust = true)
        // Not with "Send personal content to helpers automatically", not to a trusted helper (the design's default)...
        assertFalse(HelperTrust.conversation(s.copy(personalToHelpers = true), trusted, p))
        assertFalse(HelperTrust.conversation(s, trusted, p))
        assertFalse(HelperTrust.joinWithoutCard(trusted, p, misheard = false))
        // ...unless Settings › Memory shares it with helpers you trust, and only with those.
        assertTrue(HelperTrust.conversation(s, trusted, p, memoryToTrusted = true))
        assertFalse(HelperTrust.conversation(s, HelperConfig.claude(), p, memoryToTrusted = true))
        assertTrue(HelperTrust.joinWithoutCard(trusted, p, misheard = false, memoryToTrusted = true))
        // Escalating by itself sends only the question.
        val blocks = HelperContext.escalation("What about Leo?", turns, java.time.ZonedDateTime.now(utc), "CA", personal = p,
            includePersonal = HelperTrust.conversation(s, trusted, p))
        assertEquals(2, blocks.size)
        // A conversation without memory is as before.
        assertTrue(HelperTrust.conversation(s, trusted, emptySet()))
    }

    /**
     * 1.0.2 (the phone's "What's the garage code?" escalated by itself): with Automatic, "Send personal content to helpers
     * automatically" and "Voice yes can send personal content" all on, an escalation from a turn that was given an item
     * carries the context line and the question only: never the item's words, never Robot's answer, never the last turns.
     */
    @Test fun anAutomaticEscalationAfterAMemoryTurnSendsOnlyTheQuestion() {
        val all = RobotSettings().copy(personalToHelpers = true, voiceYesPersonal = true)
        val earlier = listOf(ChatTurn("u0", TurnRole.USER, "What's the weather like?", 1), ChatTurn("r0", TurnRole.ASSISTANT, "Sunny, 18 degrees.", 2))
        // The turn as PanelController holds it when Gemma calls ask_claude: the question carries the ids it was given.
        val r = MemoryRecall.forTurn("What's the door code at the cottage?", file, on, locked = false, now = now, zone = utc)
        assertTrue(door.id in r.ids)
        val question = ChatTurn("u1", TurnRole.USER, "What's the door code at the cottage?", 3, memoryIds = r.ids)
        val reply = ChatTurn("r1", TurnRole.ASSISTANT, "The door code at the cottage is 4512.", 4)
        val p = HelperContext.personal(earlier + question + reply)
        assertEquals(setOf(Personal.MEMORY), p)
        for (h in listOf(HelperConfig.claude(), HelperConfig.claude().copy(trust = true))) {
            val include = HelperTrust.conversation(all, h, p)
            assertFalse(include)
            // The escalation's history is what came before the question (PanelController's snapshot), and none of it goes.
            val blocks = HelperContext.escalation("What's the door code at the cottage?", earlier, java.time.ZonedDateTime.now(utc), "CA", personal = p,
                includePersonal = include)
            val sent = blocks.joinToString("\n") { (it as app.heyrobot.core.HelperBlock.Text).text }
            assertEquals(2, blocks.size)
            assertFalse(sent.contains("4512"))
            assertFalse(sent.contains("Sunny"))
            assertTrue(sent.endsWith("What's the door code at the cottage?"))
        }
        // A trusted helper with "Share memory with helpers: With helpers I trust": the last turns, never a private item
        // (only unlocked and named does one reach the model, and its answer is kept as a line: MemoryHygiene).
        assertTrue(HelperTrust.conversation(all, HelperConfig.claude().copy(trust = true), p, memoryToTrusted = true))
    }

    /**
     * 1.0.2, corrected in 1.1: the pinned items are in every turn's system prompt, so Robot's answer may repeat one. Their
     * ids go on the reply (PanelController), which keeps the conversation personal (a later escalation sends the question
     * alone) and never renders notes, so an item unpinned later never comes back as a note.
     */
    @Test fun pinnedItemsMakeTheConversationPersonalWithoutNotesAgain() {
        val r = MemoryRecall.forTurn("What should we do this weekend?", file, on, locked = false, now = now, zone = utc)
        assertEquals(emptyList<String>(), r.packed.retrievedIds)
        assertEquals(listOf(sam.id), r.ids)
        assertTrue(r.packed.personal)
        val turns = listOf(ChatTurn("u1", TurnRole.USER, "What should we do this weekend?", 1, memoryIds = r.packed.retrievedIds),
            ChatTurn("r1", TurnRole.ASSISTANT, "Sam, how about a hike?", 2, memoryIds = r.ids))
        val p = HelperContext.personal(turns)
        assertEquals(setOf(Personal.MEMORY), p)
        assertFalse(HelperTrust.conversation(RobotSettings().copy(personalToHelpers = true), HelperConfig.claude(), p))
        assertEquals(2, HelperContext.escalation("And next weekend?", turns, java.time.ZonedDateTime.now(utc), "CA", personal = p).size)
        // The history the model gets is byte for byte what it was before the pinned ids were kept: a reply's ids never
        // render notes (MemoryPanel.forTurn), and the question never carried Sam's.
        val notes = { ids: List<String> -> PromptPacking.notesFor(ids, file.items, now, utc) }
        val before = listOf(turns[0], turns[1].copy(memoryIds = emptyList())).toPromptTurns(notes)
        assertEquals(before, turns.toPromptTurns(notes))
        // A question's retrieved item does come back as a note.
        val withJulie = listOf(turns[0].copy(memoryIds = listOf(julie.id)), turns[1])
        assertTrue(withJulie.toPromptTurns(notes)[0].text.length > turns.toPromptTurns(notes)[0].text.length)
    }

    /**
     * 1.1 (the phone, 19 Oct: "What is my test word?" went to the paid helper by itself, helper_escalation trigger=LOCAL_TOOL
     * decision=AUTO). With Automatic, Gemma handing over sends Robot to its own answer first, with Memory's notes for the
     * question: that answer stands and no helper is asked. The panel's chain (PanelEscalation.afterLocal) as pure code.
     */
    @Test fun automaticAnswersFromMemoryBeforeAskingTheHelper() {
        val word = item("m_fact00000003", "My test word is banana")
        val f = MemoryFile(file.items + word)
        val q = "What is my test word?"
        // Memory finds the item for the question, and its words go before it to the model (both runs of it).
        val r = MemoryRecall.forTurn(q, f, on, locked = false, now = now, zone = utc)
        assertEquals(listOf(word.id), r.packed.retrievedIds)
        assertTrue(r.packed.notesBlock!!.contains("My test word is banana"))
        // Automatic with a key: the helper would be asked by itself.
        val auto = EscalationPolicy.decide(claudeSettings(), keySaved = true, personal = false, locked = false, budgetReached = false)
        assertEquals(EscalationDecision.AUTO, auto)
        // Gemma called ask_claude without a word: Robot's own answer runs first, even with Automatic.
        assertTrue(EscalationPolicy.ownAnswerFirst(handedOver = true, said = false))
        // It answered from the note: no helper call.
        val own = "Your test word is banana."
        assertEquals(EscalationDecision.NONE, EscalationPolicy.afterOwnAnswer(auto, answered = true, hedged = EscalationPolicy.ownAnswerHedged(own, acted = false)))
        // Had it not known, the helper would be asked as before.
        assertEquals(EscalationDecision.AUTO, EscalationPolicy.afterOwnAnswer(auto, answered = true,
            hedged = EscalationPolicy.ownAnswerHedged("I don't know your test word.", acted = false)))
    }

    /**
     * 1.1: the helper asked after Robot's own answer used Memory still gets the question alone. The question carries the
     * item's id (PanelController, before the model runs), so the conversation is personal (Personal.MEMORY); the escalation is
     * made from the turns before the question, and Robot's unsure answer, which repeats the item, is never part of it.
     */
    @Test fun aHelperAskedAfterAnOwnAnswerFromMemoryGetsTheQuestionAlone() {
        val word = item("m_fact00000003", "My test word is banana")
        val f = MemoryFile(file.items + word)
        val q = "What is my test word, and what does it mean in Swahili?"
        val r = MemoryRecall.forTurn(q, f, on, locked = false, now = now, zone = utc)
        assertTrue(word.id in r.packed.retrievedIds)
        val earlier = listOf(ChatTurn("u0", TurnRole.USER, "What's the weather like?", 1), ChatTurn("r0", TurnRole.ASSISTANT, "Sunny, 18 degrees.", 2))
        val question = ChatTurn("u1", TurnRole.USER, q, 3, memoryIds = r.packed.retrievedIds)
        val own = ChatTurn("r1", TurnRole.ASSISTANT, "Your test word is banana, but I'm not sure what it means in Swahili.", 4, memoryIds = r.ids)
        val auto = EscalationPolicy.decide(claudeSettings(), keySaved = true, personal = false, locked = false, budgetReached = false)
        assertEquals(EscalationDecision.AUTO, EscalationPolicy.afterOwnAnswer(auto, answered = true, hedged = EscalationPolicy.ownAnswerHedged(own.text, acted = false)))
        val p = HelperContext.personal(earlier + question + own)
        assertEquals(setOf(Personal.MEMORY), p)
        val all = RobotSettings().copy(personalToHelpers = true, voiceYesPersonal = true)
        for (h in listOf(HelperConfig.claude(), HelperConfig.claude().copy(trust = true))) {
            val include = HelperTrust.conversation(all, h, p)
            assertFalse(include)
            val blocks = HelperContext.escalation(q, earlier, java.time.ZonedDateTime.now(utc), "CA", personal = p, includePersonal = include)
            val sent = blocks.joinToString("\n") { (it as app.heyrobot.core.HelperBlock.Text).text }
            assertEquals(2, blocks.size)
            assertFalse(sent.contains("banana"))
            assertFalse(sent.contains("Sunny"))
            assertTrue(sent.endsWith(q))
        }
    }

    /** 1.0.2: what the model's remember card saved makes the conversation personal, as the router's "remember that …" does. */
    @Test fun aRememberCardDoneMakesTheConversationPersonal() {
        fun reply(status: app.heyrobot.core.ActionStatus) = ChatTurn("r1", TurnRole.ASSISTANT, "I'll remember your test word is banana.", 2,
            actions = listOf(app.heyrobot.core.ActionRecord(ToolName.REMEMBER, "Remembered", status)))
        val q = ChatTurn("u1", TurnRole.USER, "Keep in mind my test word is banana", 1)
        assertEquals(setOf(Personal.MEMORY), HelperContext.personal(listOf(q, reply(app.heyrobot.core.ActionStatus.DONE))))
        assertEquals(emptySet<Personal>(), HelperContext.personal(listOf(q, reply(app.heyrobot.core.ActionStatus.DECLINED))))
    }

    // ------------------------------------------------------------------ what goes with a question

    @Test fun recallPacksPinnedAndFoundItems() {
        val r = MemoryRecall.forTurn("When is Julie's birthday?", file, on, locked = false, now = now, zone = utc)
        assertEquals("About the user (they told you this, in their words; use it when it helps): “Call me Sam.”", r.packed.systemLine)
        assertEquals(listOf(julie.id), r.packed.retrievedIds)
        assertEquals(listOf(sam.id, julie.id), r.ids)
        assertFalse(r.secret)
        assertTrue(r.packed.notesBlock!!.contains("My sister Julie's birthday is May 3."))
        // Already in this live conversation: not again.
        assertNull(MemoryRecall.forTurn("When is Julie's birthday?", file, on, false, now, alreadyGiven = setOf(julie.id), zone = utc).packed.notesBlock)
        // Off, not used in answers, or locked without the lock screen's switch: nothing at all.
        for (s in listOf(on.copy(on = false), on.copy(useInAnswers = false))) {
            assertEquals(PackedMemory.NONE, MemoryRecall.forTurn("When is Julie's birthday?", file, s, false, now, zone = utc).packed)
        }
        assertEquals(PackedMemory.NONE, MemoryRecall.forTurn("When is Julie's birthday?", file, on, locked = true, now = now, zone = utc).packed)
        assertEquals(listOf(julie.id), MemoryRecall.forTurn("When is Julie's birthday?", file, on.copy(onLockScreen = true), true, now, zone = utc).packed.retrievedIds)
    }

    @Test fun secretsOnlyWhenNamedAndUnlocked() {
        val q = "What's the door code at the cottage?"
        val open = MemoryRecall.forTurn(q, file, on, locked = false, now = now, zone = utc)
        assertEquals(listOf(door.id), open.packed.retrievedIds)
        assertTrue(open.secret)
        // On the lock screen (allowed), never.
        val locked = MemoryRecall.forTurn(q, file, on.copy(onLockScreen = true), locked = true, now = now, zone = utc)
        assertTrue(door.id !in locked.packed.retrievedIds)
        assertEquals(1, locked.packed.secretsLeftOut)
        // Not named: left out.
        assertTrue(door.id !in MemoryRecall.forTurn("What's happening at the cottage?", file, on, false, now, zone = utc).packed.retrievedIds)
    }

    @Test fun shortContextModelsGetASmallerShare() {
        assertEquals(PackBudget(), MemoryRecall.budget(ContextWindow.MAX_TOKENS))
        val small = MemoryRecall.budget(2048)
        assertTrue(small.pinnedTokens < 100 && small.retrievedTokens < 150 && small.maxRetrieved <= 4)
        val many = MemoryFile((1..10).map { item("m_fact0000%04d".format(it), "Julie note number $it about hockey and birthdays") })
        val r = MemoryRecall.forTurn("Julie hockey birthdays", many, on, false, now, maxTokens = 2048, zone = utc)
        assertTrue(r.packed.notesTokens <= small.retrievedTokens && r.packed.retrievedIds.size <= 4)
    }

    @Test fun theSystemPromptGetsItsLinesAtTheEnd() {
        assertEquals("Base.", MemoryPrompt.system("Base.", tools = false, pinned = null))
        assertEquals("Base. ${MemoryPrompt.TOOLS}\nAbout the user: “Call me Sam.”", MemoryPrompt.system("Base.", tools = true, pinned = "About the user: “Call me Sam.”"))
    }

    // ------------------------------------------------------------------ the tools

    @Test fun rememberAndForgetAreSmallTools() {
        val r = ToolCalls.parse(RawToolCall("remember", mapOf("text" to "  I prefer   Celsius ")))
        assertEquals(Action.Remember("I prefer Celsius", MemoryKind.PREFERENCE), (r as ParsedCall.Ok).action)
        assertEquals(MemoryKind.FACT, ((ToolCalls.parse(RawToolCall("remember", mapOf("text" to "Julie likes tulips"))) as ParsedCall.Ok).action as Action.Remember).kind)
        assertEquals(Until.TONIGHT, ((ToolCalls.parse(RawToolCall("remember", mapOf("text" to "I parked on P3", "until" to "tonight"))) as ParsedCall.Ok).action as Action.Remember).until)
        assertTrue(ToolCalls.parse(RawToolCall("remember", emptyMap())) is ParsedCall.Invalid)
        assertEquals(Action.Forget("sister's birthday"), (ToolCalls.parse(RawToolCall("forget", mapOf("query" to "sister's birthday"))) as ParsedCall.Ok).action)
        // The card's summary (which History keeps) never holds the words; its body does.
        val a = Action.Remember("My sister's birthday is May 3")
        assertEquals("Remember this", a.describe())
        assertEquals("My sister's birthday is May 3", a.body())
        assertFalse(a.toString().contains("sister"))
        assertEquals("Forget from memory", Action.Forget("x", ids = listOf("m_a"), shown = "• Julie").describe())
        assertEquals(Until.TOMORROW, Until.of("until tomorrow"))
        assertEquals(Until.END_OF_WEEK, Until.of("cette semaine"))
        assertNull(Until.of("forever"))
        assertNull(Until.of(null))
    }

    @Test fun theToolsAreDescribedOnlyWithMemoryOnAndItsWords() {
        val d = RobotSettings()
        assertTrue(ToolGroup.MEMORY in ToolRouter.route("My sister's name is Julie, remember that"))
        assertTrue(ToolGroup.MEMORY in ToolRouter.route("oublie ce que je t'ai dit sur Julie"))
        assertFalse(ToolGroup.MEMORY in ToolRouter.route("Remember to buy milk"))
        assertFalse(ToolGroup.MEMORY in ToolRouter.route("What's the weather tomorrow?"))
        val groups = setOf(ToolGroup.CORE, ToolGroup.MEMORY)
        assertFalse(ToolName.REMEMBER in ToolRouter.tools(groups, d))
        assertEquals(listOf(ToolName.REMEMBER, ToolName.FORGET), ToolRouter.tools(groups, d, memory = true).filter { it.group == ToolGroup.MEMORY })
        assertTrue(ToolName.REMEMBER in ToolRouter.asked("My sister's name is Julie, remember that"))
        assertFalse(ToolName.REMEMBER in ToolRouter.asked("My sister's name is Julie"))
        assertTrue(ToolName.FORGET in ToolRouter.asked("forget what I said about Julie"))
        // Robot's own (not under Settings › Actions), never a helper's.
        assertTrue(ToolName.REMEMBER.internal && ToolName.FORGET.internal)
        assertFalse(ToolName.REMEMBER in app.heyrobot.core.HelperTools.OFFERED || ToolName.FORGET in app.heyrobot.core.HelperTools.OFFERED)
    }

    @Test fun aMemoryTheModelProposesIsACard() {
        val d = RobotSettings()
        // Your own words asked: it just runs (Undo on its card).
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.REMEMBER, false, d, asked = true))
        // The model on its own, or a conversation with content (a screen, a message, a helper's words): a card.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.REMEMBER, false, d, asked = false))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.REMEMBER, false, d, content = true, asked = true))
        // Forget always shows its card; neither works on the lock screen through the model.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.FORGET, false, d, asked = true))
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.REMEMBER, true, d, asked = true))
    }

    // ------------------------------------------------------------------ memory.json

    @Test fun memorySettingsLiveInTheirOwnFile() {
        val s = MemorySettings(on = false, useInAnswers = false, suggestions = SuggestionStyle.OFF, onLockScreen = true, speakSecrets = true,
            helpers = HelperMemory.TRUSTED_HELPERS, searchModel = SearchModelOffer.DECLINED)
        assertEquals(s, MemorySettings.fromJson(s.toJson()))
        assertEquals(MemorySettings(), MemorySettings.fromJson(null))
        assertEquals(MemorySettings(), MemorySettings.fromJson("{not json"))
        assertEquals(MemorySettings(), MemorySettings.fromJson("""{"on":"yes","helpers":"EVERYONE","suggestions":7}"""))
        assertEquals("memory.json", MemorySettings.FILE)
        // Never in settings.json: its schema stays 13.
        assertEquals(13, RobotSettings.SCHEMA)
        assertFalse(RobotSettings().toJson().toString().contains("onLockScreen"))
    }
}
