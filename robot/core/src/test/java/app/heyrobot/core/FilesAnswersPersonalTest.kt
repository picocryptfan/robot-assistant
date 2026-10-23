// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.web.QueryCard
import app.heyrobot.core.web.QueryCheck
import app.heyrobot.core.web.QueryVerdict
import app.heyrobot.core.web.WebQueryContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What Robot Files' router answers by itself (no tool: "You spent $312.40 on gas", a due date, a drawer's name; ChatTurn.files)
 * is your files: personal, and like a tool's findings in your files it reaches a helper only through a card a tap answers
 * (Personal.FILES, HelperTrust). Every way a conversation goes to a helper or a search service is checked here.
 */
class FilesAnswersPersonalTest {
    private val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val total = "You spent \$312.40 on gas in September, from 9 receipts at Ultramar."
    private val turns = listOf(
        ChatTurn("u1", TurnRole.USER, "How much did I spend on gas in September?", 1),
        ChatTurn("r1", TurnRole.ASSISTANT, total, 2, files = true),
    )
    private val follow = "Explain why gas costs so much more this year"
    private fun texts(blocks: List<HelperBlock>) = blocks.filterIsInstance<HelperBlock.Text>().joinToString("\n") { it.text }

    /** Every switch that lets other personal content go without a tap: on. */
    private val trusted = HelperConfig.of(Presets.HOME, "h2", "af_bella", "Jarvis", "http://192.168.1.20:11434/v1").copy(trust = true)
    private val open = RobotSettings(personalToHelpers = true, voiceYesPersonal = true).withHelper(trusted)

    @Test fun aFilesAnswerMakesTheConversationPersonal() {
        assertEquals(setOf(Personal.FILES), HelperContext.personal(turns))
        // The same reply without the mark (an ordinary answer) is not.
        assertEquals(emptySet<Personal>(), HelperContext.personal(turns.map { it.copy(files = false) }))
        // And it stays so after History: the mark is saved and read back.
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(StoredConversation("c1", 1, 2, turns)))
        assertNotNull(back)
        assertEquals(setOf(Personal.FILES), HelperContext.personal(back!!.turns))
    }

    @Test fun robotCheckingByItselfSendsOnlyTheQuestionWhateverTheSwitches() {
        val personal = HelperContext.personal(turns)
        for (s in listOf(RobotSettings(), open)) for (h in listOf(null, trusted)) {
            val include = HelperTrust.conversation(s, h, personal, memoryToTrusted = true)
            assertFalse("$h", include)
            val sent = texts(HelperContext.escalation(follow, turns, now, "CA", personal = personal, includePersonal = include))
            assertFalse(sent, sent.contains("312.40") || sent.contains("Ultramar") || sent.contains("receipts"))
            assertTrue(sent, sent.endsWith(follow))
            // An offer's payload: the same, and its card doesn't list the earlier turns.
            val p = HelperContext.payload(follow, turns, now, "CA", personal, includePersonal = include)
            assertFalse(texts(p.blocks).contains("312.40"))
            assertEquals(1, p.sends.size)
        }
    }

    @Test fun aSpokenYesNeverSendsItAndJoiningAsksFirst() {
        val personal = HelperContext.personal(turns)
        assertFalse(HelperTrust.voiceYes(open, trusted, personal))
        assertFalse(HelperTrust.joinWithoutCard(trusted, personal, misheard = false, memoryToTrusted = true))
        // "Go get Jarvis": the Include card names your files, and only its tap sends them.
        assertEquals("Include the conversation so far? It contains your files.", HelperContext.includeQuestion(turns))
        assertFalse(texts(HelperContext.join(turns, null, now, "CA")).contains("312.40"))
        assertTrue(texts(HelperContext.join(turns, null, now, "CA", include = true)).contains("312.40"))
    }

    @Test fun theAppHandOffQuotesOnlyYourQuestion() {
        val s = AppHandOff.summary(turns)!!
        assertTrue(s, s.contains("How much did I spend on gas in September"))
        assertFalse(s, s.contains("312.40") || s.contains("It said"))
    }

    @Test fun aSearchWithAWordFromTheAnswerNeedsATap() {
        val asked = turns + ChatTurn("u2", TurnRole.USER, "search the web for cheaper gas stations", 3)
        val ctx = WebQueryContext.of(asked, "search the web for cheaper gas stations")
        assertTrue(ctx.isPersonal)
        assertTrue(ctx.personal.contains(total))
        val v = QueryCheck.check("Ultramar gas prices", ctx)
        assertTrue("$v", v is QueryVerdict.Card && v.why == QueryCard.PERSONAL && QueryCard.PERSONAL.tapOnly)
        assertEquals(listOf("Ultramar"), (v as QueryVerdict.Card).words)
        // Your own words still go.
        assertEquals(QueryVerdict.Send("cheaper gas stations"), QueryCheck.check("cheaper gas stations", ctx))
    }
}
