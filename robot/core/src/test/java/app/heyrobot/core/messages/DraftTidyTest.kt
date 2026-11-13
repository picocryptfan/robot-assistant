// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTidyTest {
    private fun t(s: String, names: List<String> = emptyList()) = DraftTidy.tidy(s, names)

    @Test fun fillersGo() {
        assertEquals("Yes, I'll come", t("Yes, um, I'll come").text)
        assertEquals("I'm late", t("Um, I'm late").text)
        assertEquals("on my way home.", t("on my way home, uh.").text)
        assertEquals("see you soon", t("uh see you uhm soon").text)
        assertEquals("je suis en route", t("euh je suis heu en route").text)
        // Not fillers: words that only start like them.
        assertEquals("umbrella here", t("umbrella here").text)
        assertTrue(t("Yes, um, I'll come").marks.isEmpty())
    }

    /** review-after13-b: a filler is lower case and alone, or capitalised only to start a sentence; never initials. */
    @Test fun capitalisedWordsAreNotFillers() {
        assertEquals("I'm at the ER", t("I'm at the ER").text)
        assertEquals("go to the ER, now", t("go to the ER, uh, now").text)
        assertEquals("He studies at UM, and HM too", t("He studies at UM, and HM too").text)
        assertEquals("Sign UH and ERM here", t("Sign UH and ERM here").text)
        assertEquals("Meet at the OR, US time, AH signs", t("Meet at the OR, US time, AH signs").text)
        // Capitalised inside a sentence: a word, not a filler.
        assertEquals("ask Hm about it", t("ask Hm about it").text)
        // French: the same.
        assertEquals("Je suis à l'urgence, code EUH", t("Je suis à l'urgence, code EUH").text)
        assertEquals("Je suis là", t("Euh, je suis là").text)
        assertEquals("Je pars. bon, on y va", t("Je pars. Euh, bon, on y va").text)
        assertEquals("I'm late. sorry", t("I'm late. Um, sorry").text)
    }

    /** Another app's dictation: fillers out, corrections as said (they may mean both). */
    @Test fun fillersOnlyKeepsCorrections() {
        val f = DraftTidy::fillersOnly
        assertEquals("I can't make it Friday, actually Saturday works", f("I can't make it Friday, actually Saturday works"))
        assertEquals("Thanks Sam, sorry Jim couldn't come", f("Thanks Sam, sorry Jim couldn't come"))
        assertEquals("I'll be, ten, no, fifteen minutes late", f("I'll be, uh, ten, no, fifteen minutes late"))
        assertEquals("I'm late", f("Um, I'm late"))
        assertEquals("I'm at the ER", f("I'm at the ER"))
        assertEquals("je serai là à, cinq, non, six heures", f("je serai là à, euh, cinq, non, six heures"))
        assertEquals("Je suis à l'UM, mardi ou plutôt mercredi", f("Je suis à l'UM, euh, mardi ou plutôt mercredi"))
    }

    @Test fun lastNumberKept() {
        val r = t("I'll be, uh, ten, no, fifteen minutes late")
        assertEquals("I'll be fifteen minutes late", r.text)
        assertEquals(listOf(DraftMark("fifteen minutes", "ten")), r.marks)
        // Speech to text writes no commas.
        assertEquals("ill be fifteen minutes late", t("ill be uh ten no fifteen minutes late").text)
        // Several corrections: the first word is what's marked as was.
        val r2 = t("bring 3 no 4 I mean 5 chairs")
        assertEquals("bring 5 chairs", r2.text)
        assertEquals(listOf(DraftMark("5", "3")), r2.marks)
    }

    @Test fun lastTimeKept() {
        assertEquals("meet at 6 pm", t("meet at 5 pm, sorry, 6 pm").text)
        assertEquals("meet at 6:30", t("meet at 6, actually 6:30").text)
        assertEquals("ten minutes no problem", t("ten minutes no problem").text)
    }

    @Test fun lastNameKept() {
        val r = t("Tell Sam, no, Sarah to call me")
        assertEquals("Tell Sarah to call me", r.text)
        assertEquals(listOf(DraftMark("Sarah", "Sam")), r.marks)
        assertEquals("see you Wednesday", t("see you Tuesday, I mean Wednesday").text)
        // A lowercase name counts when Robot knows it.
        assertEquals("ask sarah", t("ask sam no sarah", listOf("Sam Lee", "Sarah")).text)
        // Not a correction: "no" between ordinary words, or a capital at the start.
        assertEquals("Sorry, no, I can't come", t("Sorry, no, I can't come").text)
        assertEquals("I said no to Bob", t("I said no to Bob").text)
    }

    @Test fun french() {
        val r = t("Je serai là à, euh, cinq, non, six heures")
        assertEquals("Je serai là à six heures", r.text)
        assertEquals(listOf(DraftMark("six heures", "cinq")), r.marks)
        assertEquals("on se voit jeudi", t("on se voit mercredi, je veux dire jeudi").text)
        assertEquals("apporte 4 chaises", t("apporte 3, pardon, 4 chaises").text)
        assertEquals("Non, merci", t("Non, merci").text)
    }

    @Test fun rewordRequests() {
        assertEquals("warmer, and say I'm bringing dessert", DraftReword.request("warmer, and say I'm bringing dessert"))
        listOf("a bit shorter", "make it more formal", "Say I'm bringing dessert", "plus chaleureux", "ajoute que j'apporte le dessert",
            "rends-le plus court", "and mention the dog").forEach { assertTrue(it, DraftReword.request(it) != null) }
        listOf("yes", "send it", "cancel", "what time is it", "text Sam hi").forEach { assertNull(it, DraftReword.request(it)) }
    }

    @Test fun rewordKeepsFacts() {
        val before = "I'll be fifteen minutes late"
        val ask = "warmer, and say I'm bringing dessert"
        val ok = DraftReword.check(before, "Hi Sam! So sorry, I'll be 15 minutes late. I'm bringing dessert!", ask, "Sam")
        assertTrue(ok.ok)
        assertTrue(ok.marks.isEmpty())
        // Dropped the time, or made one up: thrown away.
        assertFalse(DraftReword.check(before, "Hi Sam! Running a little late, bringing dessert!", ask, "Sam").ok)
        assertFalse(DraftReword.check(before, "I'll be 20 minutes late, bringing dessert", ask, "Sam").ok)
        assertFalse(DraftReword.check(before, "I'll be 15 minutes late. Love, Alex", ask, "Sam").ok)
        // A change you asked for is kept, and marked.
        val asked = DraftReword.check(before, "I'll be 20 minutes late", "say twenty minutes instead", "Sam")
        assertTrue(asked.ok)
        assertEquals(listOf(DraftMark("20", "fifteen")), asked.marks)
        // French.
        assertTrue(DraftReword.check("Je serai là à six heures", "Coucou! Je serai là à 6 heures, j'apporte le dessert.", "plus chaleureux", "Sam").ok)
    }

    @Test fun cleanAnswer() {
        assertEquals("Hi Sam!", DraftReword.clean("Here's a warmer version: \"Hi Sam!\""))
        assertEquals("Salut", DraftReword.clean("« Salut »\n\nJ'ai gardé l'heure."))
        assertNull(DraftReword.clean("  "))
    }
}
