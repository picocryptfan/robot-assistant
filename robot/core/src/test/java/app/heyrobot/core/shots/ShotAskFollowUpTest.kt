// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-train-14 (phone-train-14, screenshots 2): the second screenshot question in a conversation went to the model ("I
// cannot see any screenshots"): the first answer's placeholder made the conversation untrusted, and the index stood aside.
class ShotAskFollowUpTest {
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN)
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)

    @Test fun aSecondScreenshotQuestionStillReachesTheIndex() {
        val q1 = ChatTurn("u1", TurnRole.USER, "what is my PIN in my screenshots", 1L)
        val a1 = ShotAsk.forHistory(ChatTurn("a1", TurnRole.ASSISTANT, "I didn't find that.", 2L), en)
        val q2 = ChatTurn("u2", TurnRole.USER, "when was my last login in my screenshots", 3L)
        assertTrue(TurnRules.untrusted(listOf(q1, a1, q2)))
        assertFalse(ShotAsk.untrusted(listOf(q1, a1, q2)))
        assertFalse(ShotAsk.untrusted(listOf(q1, ShotAsk.forHistory(a1.copy(text = "x"), fr), q2)))
    }

    @Test fun anythingElseRobotDidNotWriteStillKeepsTheIndexOut() {
        val q = ChatTurn("u", TurnRole.USER, "what's in my screenshots", 3L)
        val shot = ShotAsk.forHistory(ChatTurn("a1", TurnRole.ASSISTANT, "x", 2L), en)
        for (t in listOf(ChatTurn("h", TurnRole.ASSISTANT, "x", 1L, fromHelper = true), ChatTurn("m", TurnRole.ASSISTANT, "x", 1L, messages = true, kept = "[read]"),
                shot.copy(kept = "[other]", text = "[other]"), shot.copy(fromHelper = true), shot.copy(text = "the PIN is 4821"),
                shot.copy(locked = true)))
            assertTrue(t.id, ShotAsk.untrusted(listOf(t, shot, q)))
    }

    @Test fun aYearBesideLastLoginIsADateNotACode() {
        for (line in listOf("Last login: Nov 9, 2026", "Last login 9 Nov 2026 at 10:42", "Dernière connexion : 9 nov. 2026", "Last login: 2026-11-09", "Last login 11/09/2026"))
            assertEquals(line, line, ShotSecrets.hide(line).text)
        assertEquals("Login code: [sign-in code]", ShotSecrets.hide("Login code: 2026").text)
        // L3: a word that only starts like a month is no month, so its "12, 2026" stays a code on a code line.
        for (w in listOf("mail", "main", "market", "novel", "octet"))
            assertTrue(w, "[sign-in code]" in ShotSecrets.hide("Login code: $w 12, 2026").text)
        for (line in listOf("Last login: March 9, 2026", "Last login: September 9, 2026", "Dernière connexion : 9 juillet 2026", "Last login: Sept. 9, 2026",
                // review-batch-14-rest-b: French abbreviations with a dot, "févr." the one the month list had lost.
                "Dernière connexion : 9 févr. 2026", "Dernière connexion : 9 janv. 2026", "Dernière connexion : 9 juil. 2026",
                "Dernière connexion : 9 déc. 2025", "Dernière connexion : 9 février 2026"))
            assertEquals(line, line, ShotSecrets.hide(line).text)
        assertEquals("Sign-in code [sign-in code], sent Nov 9, 2026", ShotSecrets.hide("Sign-in code 482913, sent Nov 9, 2026").text)
    }
}
