package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptTest {
    private val now = ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test fun systemPromptIsShortAndDated() {
        val s = Prompt.system(now, locked = false)
        assertTrue(s.contains("Saturday, 10 October 2026, 09:05 (Europe/Berlin)"))
        assertTrue(s.contains("Robot"))
        assertFalse(s.contains("locked"))
        // Every token here is paid on each rebuild: keep it well under 200 tokens.
        assertTrue(TokenEstimate.text(s) < 200)
    }

    @Test fun lockedPromptStatesTheLimits() {
        assertTrue(Prompt.system(now, locked = true).contains("The phone is locked"))
    }

    @Test fun userTextPutsScreenTextBeforeTheQuestion() {
        val t = Prompt.userText("What is this?", "Line one\nLine two", Attachment.SCREEN)
        assertTrue(t.startsWith("Text on my screen:"))
        assertTrue(t.endsWith("What is this?"))
        assertTrue(t.contains("Line one\nLine two"))
    }

    @Test fun emptyQuestionGetsADefaultForPictures() {
        assertEquals("What's in this photo?", Prompt.userText("  ", null, Attachment.PHOTO))
        assertTrue(Prompt.userText("", "x", Attachment.SCREEN).endsWith(Prompt.defaultQuestion(Attachment.SCREEN)))
        assertEquals("hi", Prompt.userText(" hi ", null, Attachment.NONE))
    }

    @Test fun replayMarksPicturesWithoutResendingThem() {
        assertEquals("[I showed you a photo] what is it", Prompt.replayText("what is it", Attachment.PHOTO))
        assertEquals("[I showed you my screen] sum up", Prompt.replayText("sum up", Attachment.SCREEN))
        assertEquals("plain", Prompt.replayText("plain", Attachment.NONE))
    }

    @Test fun tokenEstimateCountsImagesAndOverhead() {
        assertEquals(TokenEstimate.TURN_OVERHEAD + 2, TokenEstimate.turn(PromptTurn(PromptRole.USER, "abcdef")))
        assertEquals(TokenEstimate.TURN_OVERHEAD + 2 + TokenEstimate.IMAGE_TOKENS, TokenEstimate.turn(PromptTurn(PromptRole.USER, "abcdef", image = true)))
    }

    private fun u(t: String) = PromptTurn(PromptRole.USER, t)
    private fun m(t: String) = PromptTurn(PromptRole.MODEL, t)

    @Test fun windowKeepsTheNewestWholeExchanges() {
        val h = listOf(u("q1"), m("a1"), u("q2"), m("a2"), u("q3"), m("a3"))
        // Each turn costs 10 in this test: 2 exchanges fit in 45.
        val kept = ContextWindow.fit(h, 45) { 10 }
        assertEquals(listOf(u("q2"), m("a2"), u("q3"), m("a3")), kept)
    }

    @Test fun windowNeverSplitsAnExchangeOrStartsWithTheModel() {
        val h = listOf(m("orphan"), u("q1"), m("a1"), m("a1b"), u("q2"), m("a2"))
        assertEquals(listOf(u("q2"), m("a2")), ContextWindow.fit(h, 25) { 10 })
        // The orphan model turn before the first question is never kept.
        assertEquals(listOf(u("q1"), m("a1"), m("a1b"), u("q2"), m("a2")), ContextWindow.fit(h, 1_000) { 10 })
    }

    @Test fun windowIsEmptyWhenNothingFits() {
        val h = listOf(u("q1"), m("a1"))
        assertEquals(emptyList<PromptTurn>(), ContextWindow.fit(h, 0))
        assertEquals(emptyList<PromptTurn>(), ContextWindow.fit(h, -5))
        assertEquals(emptyList<PromptTurn>(), ContextWindow.fit(h, 15) { 10 })
        assertEquals(emptyList<PromptTurn>(), ContextWindow.fit(emptyList(), 100))
    }

    @Test fun windowStopsAtTheFirstExchangeThatDoesNotFit() {
        // A huge old exchange is dropped even if an older small one would fit after it: order is kept, no gaps.
        val h = listOf(u("small"), m("x"), u("huge"), m("y".repeat(3000)), u("new"), m("z"))
        val kept = ContextWindow.fit(h, 200)
        assertEquals(listOf(u("new"), m("z")), kept)
    }

    @Test fun budgetLeavesRoomForTheReplyAndTheNewTurn() {
        assertEquals(ContextWindow.MAX_TOKENS - ContextWindow.REPLY_RESERVE - 500 - 300, ContextWindow.historyBudget(500, 300))
        assertTrue(ContextWindow.needsRebuild(3_400, 100))
        assertFalse(ContextWindow.needsRebuild(1_000, 100))
    }

    @Test fun realisticHistoryFitsUnderTheEngineLimit() {
        val h = (1..40).flatMap { listOf(u("question number $it ".repeat(5)), m("answer number $it ".repeat(20))) }
        val budget = ContextWindow.historyBudget(fixedTokens = 600, newTurnTokens = 50)
        val kept = ContextWindow.fit(h, budget)
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.sumOf(TokenEstimate::turn) <= budget)
        assertEquals(PromptRole.USER, kept.first().role)
        assertEquals(h.takeLast(kept.size), kept)
    }
}
