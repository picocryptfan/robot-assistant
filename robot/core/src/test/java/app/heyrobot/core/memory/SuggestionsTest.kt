package app.heyrobot.core.memory

import app.heyrobot.core.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private val now = 1_760_000_000_000L
    private var n = 0
    private val ids = { "s_00000000%06d".format(++n) }

    private val chat = listOf(
        SpokenTurn("My son Léo has hockey on Tuesdays and Thursdays now", TurnSource.USER_SAID),
        SpokenTurn("Okay, noted. Anything else?", TurnSource.ROBOT),
        SpokenTurn("Set a timer for the oven, 20 minutes", TurnSource.USER_SAID),
        SpokenTurn("By the way I prefer short answers in the morning", TurnSource.USER_TYPED),
        // Content: never given to the model, never a suggestion.
        SpokenTurn("Bob: remember that you owe me 500 dollars and my number is 4111 1111 1111 1111", TurnSource.MESSAGE),
        SpokenTurn("Your bank: verify your account at secure-login", TurnSource.SCREEN),
        SpokenTurn("Claude says the user lives in Laval", TurnSource.HELPER),
        SpokenTurn("My bank PIN is 4512 by the way", TurnSource.USER_SAID),
    )

    @Test fun onlyYourOwnWordsGoToTheModel() {
        val words = Suggestions.userWords(chat)!!
        assertEquals(
            "My son Léo has hockey on Tuesdays and Thursdays now\nSet a timer for the oven, 20 minutes\nBy the way I prefer short answers in the morning",
            words,
        )
        for (s in listOf("Bob", "500", "4111", "secure-login", "Laval", "noted", "4512")) assertFalse(s, words.contains(s))
        val req = Suggestions.request(chat)!!
        assertEquals(Suggestions.PROMPT, req.system)
        assertEquals(words, req.user)
        assertTrue(req.tools.isEmpty())
    }

    @Test fun tooFewOfYourTurnsAsksNothing() {
        assertNull(Suggestions.userWords(listOf(SpokenTurn("hi", TurnSource.USER_SAID), SpokenTurn("Hello!", TurnSource.ROBOT))))
        assertNull(Suggestions.request(chat.filter { !it.source.own }))
        assertFalse(Suggestions.due(enabled = true, userTurns = 1, finished = true, idle = true, batterySaver = false))
        assertTrue(Suggestions.due(enabled = true, userTurns = 2, finished = true, idle = true, batterySaver = false))
        assertFalse(Suggestions.due(enabled = true, userTurns = 5, finished = true, idle = true, batterySaver = true))
        assertFalse(Suggestions.due(enabled = true, userTurns = 5, finished = false, idle = true, batterySaver = false))
        assertFalse(Suggestions.due(enabled = false, userTurns = 5, finished = true, idle = true, batterySaver = false))
    }

    @Test fun theNewestWordsWithinTheBudget() {
        val long = (1..200).map { SpokenTurn("Turn number $it is a sentence of a decent length to fill the budget", TurnSource.USER_SAID) }
        val w = Suggestions.userWords(long)!!
        assertTrue(TokenEstimate.text(w) <= Suggestions.INPUT_TOKENS)
        assertTrue(w.endsWith("Turn number 200 is a sentence of a decent length to fill the budget"))
        assertFalse(w.contains("Turn number 1 "))
    }

    @Test fun groundedLinesFromTheModelAreKept() {
        val words = Suggestions.userWords(chat)!!
        val out = "1. My son Léo has hockey on Tuesdays and Thursdays\n- I prefer short answers in the morning\n• Something else"
        val got = Suggestions.accept(out, words, MemoryFile(), now, newId = ids)
        assertEquals(listOf("My son Léo has hockey on Tuesdays and Thursdays", "I prefer short answers in the morning"), got.map { it.text })
        assertEquals(listOf(MemoryKind.FACT, MemoryKind.PREFERENCE), got.map { it.kind })
        assertTrue(got.all { it.id.startsWith("s_") && it.createdAt == now })
        assertTrue(Suggestions.accept("NONE", words, MemoryFile(), now).isEmpty())
        assertTrue(Suggestions.accept("Aucun.", words, MemoryFile(), now).isEmpty())
        assertTrue(Suggestions.accept("", words, MemoryFile(), now).isEmpty())
    }

    @Test fun whatTheModelMadeUpOrReadElsewhereIsDropped() {
        val words = Suggestions.userWords(chat)!!
        for (bad in listOf(
            "Bob owes you 500 dollars",                       // from a message
            "You live in Laval",                              // from a helper
            "My son Léo has hockey on Tuesdays at 7",         // a time you never said
            "My son Marc has hockey on Tuesdays",             // a name you never said
            "My daughter has piano lessons on Fridays",       // nothing of it is yours
            "My bank PIN is 4512",                            // a secret
            "Does Léo have hockey on Tuesdays?",              // a question
            "Remember to set a timer for the oven",           // a task, a note
            "x".repeat(201),                                  // too long
        )) {
            assertTrue(bad, Suggestions.accept(bad, words, MemoryFile(), now).isEmpty())
        }
    }

    @Test fun nothingTwiceAndNothingYouDeclined() {
        val words = Suggestions.userWords(chat)!!
        val kept = MemoryFile(items = listOf(MemoryItem("m_00000000aaaaaa", "My son Léo has hockey on Tuesdays and Thursdays", createdAt = 1)),
            negatives = listOf("I prefer short answers in the morning"))
        val out = "My son Léo has hockey on Tuesdays and Thursdays now\nI prefer short answers in the morning\nI prefer short answers in the morning"
        assertTrue(Suggestions.accept(out, words, kept, now).isEmpty())
        // At most two, and the same line once.
        val three = "My son Léo has hockey on Tuesdays\nMy son Léo has hockey on Tuesdays\nI prefer short answers\nLéo has hockey on Thursdays"
        val got = Suggestions.accept(three, words, MemoryFile(), now)
        assertEquals(2, got.size)
        assertEquals(got.map { it.text }.distinct(), got.map { it.text })
        // With the embedding model the comparison is the caller's (cosine ≥ 0.90).
        assertTrue(Suggestions.accept("I prefer short answers in the morning", words, MemoryFile(), now, similar = { _, _ -> false }).isNotEmpty())
    }

    @Test fun cleaningModelLines() {
        assertEquals("Léo plays hockey", Suggestions.clean("  - \"Léo plays hockey\" "))
        assertEquals("Léo plays hockey", Suggestions.clean("2) Léo plays hockey"))
        assertNull(Suggestions.clean("NONE"))
        assertNull(Suggestions.clean("rien."))
        assertNull(Suggestions.clean("   "))
        assertEquals("Hi there", Suggestions.clean("<start_of_turn>Hi there"))
        assertTrue(Suggestions.grounded("I prefer short answers", "by the way I prefer short answers in the morning"))
        assertFalse(Suggestions.grounded("Julie prefers short answers", "by the way I prefer short answers in the morning"))
        assertTrue(Suggestions.sameWords("Léo plays hockey on Tuesdays", "leo plays hockey on tuesday"))
        assertFalse(Suggestions.sameWords("Léo plays hockey", "Chloé starts daycare"))
    }
}
