// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calls

import app.heyrobot.core.i18n.CallerStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerNameTest {
    private val n = "+19025550148"
    private val all = CallerName.scope(locked = false, someoneElse = false, textsOn = true, textsLocked = false)
    private fun src(s: CallerSource, vararg c: CallerClue): Pair<CallerSource, () -> List<CallerClue>> = s to { c.toList() }
    private fun guess(vararg s: Pair<CallerSource, () -> List<CallerClue>>, scope: CallerScope = all) =
        CallerName.guess(n, s.toList(), scope, now = { 0L })

    @Test fun sameNumberAcrossForms() {
        assertTrue(CallerName.same(n, "(902) 555-0148"))
        assertTrue(CallerName.same(n, "1-902-555-0148"))
        assertTrue(CallerName.same(n, "555-0148"))
        assertFalse(CallerName.same(n, "902-555-0149"))
        assertFalse(CallerName.same(n, "0148"))
        assertFalse(CallerName.same(null, n))
    }

    @Test fun matchFromTexts() {
        // The sender's name its app gave (a business's own name), and a name on the number's line in someone's text.
        assertEquals("Coastal Plumbing", guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, "We're on our way", about = n, name = "Coastal Plumbing"))))
        assertEquals("Coastal Plumbing", guess(src(CallerSource.TEXTS,
            CallerClue(CallerSource.TEXTS, "Try them. Call Coastal Plumbing at 902-555-0148, they're good", about = "+19025550101", name = "Sam"))))
        // A text from someone else with the number but no name next to it: never the sender's name.
        assertNull(guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, "902-555-0148", about = "+19025550101", name = "Sam"))))
        // A sender whose app gave only the number: nothing.
        assertNull(guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, "hi", about = n, name = "(902) 555-0148"))))
    }

    @Test fun matchFromCalendar() {
        assertEquals("Coastal Plumbing", guess(src(CallerSource.CALENDAR, CallerClue(CallerSource.CALENDAR, "Coastal Plumbing\n902 555 0148", title = "Coastal Plumbing"))))
    }

    @Test fun matchFromFiles() {
        val invoice = "INVOICE 2231\nHarbour Electric Ltd. Tel: (902) 555-0148\nTotal 120.00"
        assertEquals("Harbour Electric Ltd", guess(src(CallerSource.FILES, CallerClue(CallerSource.FILES, invoice, title = "invoice-2231.pdf"))))
        // The number alone on its line: never the file's name, even one shaped like a name.
        assertNull(guess(src(CallerSource.FILES, CallerClue(CallerSource.FILES, "902-555-0148", title = "Furnace warranty"))))
        assertNull(guess(src(CallerSource.FILES, CallerClue(CallerSource.FILES, "902-555-0148", title = "Bay Heating"))))
    }

    @Test fun onlyNameShapedWords() {
        fun cal(text: String, title: String = "Rendez-vous") = guess(src(CallerSource.CALENDAR, CallerClue(CallerSource.CALENDAR, text, title = title)))
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        // The review's sentences: words before the number that aren't a name show nothing.
        assertNull(txt("Please call 902-555-0148"))
        assertNull(txt("Please call 902-555-0148 when you can"))
        assertNull(txt("call me at 902-555-0148"))
        assertNull(txt("Call me at 902-555-0148"))
        assertNull(txt("my therapist's new number is 902-555-0148"))
        assertNull(txt("My new number is 902-555-0148"))
        assertNull(txt("Number: 902-555-0148"))
        assertNull(txt("Text 902-555-0148"))
        assertNull(txt("Hi! It's 902-555-0148"))
        assertNull(txt("Appelle au 902-555-0148"))
        assertNull(txt("Appelle-moi au 902-555-0148"))
        assertNull(txt("mon nouveau numéro est le 902-555-0148"))
        assertNull(txt("Voici mon numéro 902-555-0148"))
        assertNull(txt("Merci, c'est le 902-555-0148"))
        assertNull(txt("ok see you then\n902-555-0148"))
        // A name before the number still shows, with the words around it dropped.
        assertEquals("Bay Heating", txt("you could try Bay Heating at 902-555-0148"))
        assertEquals("Sam's Garage", txt("Call Sam's Garage 902-555-0148"))
        assertEquals("Café de la Gare", txt("Réserve au Café de la Gare au 902-555-0148"))
        assertEquals("Johnson & Sons", txt("Johnson & Sons: 902-555-0148"))
        // An event's title only when it's shaped like a name.
        assertEquals("Coastal Plumbing", cal("902-555-0148", title = "Coastal Plumbing"))
        assertNull(cal("902-555-0148", title = "call the furnace guy"))
        assertNull(cal("902-555-0148", title = "Rendez-vous"))
        assertNull(cal("902-555-0148", title = "Dentist appointment"))
        assertNull(cal("Please call 902-555-0148", title = "Coastal Plumbing"))
    }

    /** review-caller-name-b, Medium 1: typographic apostrophes, hyphenated verbs and labels before ":" show nothing. */
    @Test fun labelsAndVerbsShowNothing() {
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        // English.
        assertNull(txt("Hi! It\u2019s 902-555-0148"))
        assertNull(txt("Call Us: 902-555-0148"))
        assertNull(txt("CONTACT US 902-555-0148"))
        assertNull(txt("Call him at 902-555-0148"))
        assertNull(txt("Text them at 902-555-0148"))
        assertNull(txt("Ph: 902-555-0148"))
        assertNull(txt("Telephone: 902-555-0148"))
        assertNull(txt("Toll-free: 902-555-0148"))
        assertNull(txt("Dispatch: 902-555-0148"))
        // French (fr-CA invoices put a no-break space before ":").
        assertNull(txt("C\u2019est le 902-555-0148"))
        assertNull(txt("Rappelle-moi au 902-555-0148"))
        assertNull(txt("Rappelle\u2011moi au 902-555-0148"))
        assertNull(txt("Appelez-nous au 902-555-0148"))
        assertNull(txt("\u00C9cris-moi au 902-555-0148"))
        assertNull(txt("Contactez 902-555-0148"))
        assertNull(txt("Contactez-nous au 902-555-0148"))
        assertNull(txt("T\u00E9l : 902-555-0148"))
        assertNull(txt("T\u00E9l\u00A0: 902-555-0148"))
        assertNull(txt("T\u00E9l\u00E9phone\u00A0: 902-555-0148"))
        assertNull(txt("Sans frais\u00A0: 902-555-0148"))
        assertNull(txt("Accueil\u00A0: 902-555-0148"))
        // Names still show: several words before ":", a typographic apostrophe, a hyphenated first name.
        assertEquals("Johnson & Sons", txt("Johnson & Sons: 902-555-0148"))
        assertEquals("Sam\u2019s Garage", txt("Call Sam\u2019s Garage 902-555-0148"))
        assertEquals("Jean-Marc C\u00F4t\u00E9", txt("Appelle Jean-Marc C\u00F4t\u00E9 au 902-555-0148"))
    }

    /** review-caller-name-b, Low 2: a comma ends the name, and a street number makes the words an address. */
    @Test fun commaOrStreetNumberEndsTheName() {
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        assertNull(txt("Bay Heating, 12 Water St, Halifax 902-555-0148"))
        assertNull(txt("12 Water St 902-555-0148"))
        assertNull(txt("Plomberie C\u00F4t\u00E9, 45 rue King, Moncton 902-555-0148"))
        assertEquals("Bay Heating", txt("Hi Sam, Bay Heating 902-555-0148"))
        assertEquals("Plomberie C\u00F4t\u00E9", txt("Salut Marie, Plomberie C\u00F4t\u00E9 au 902-555-0148"))
    }

    /** Old Low 5: a clue matches on 10 digits only, so a 7-digit number from another area code names nobody. */
    @Test fun cluesMatchOnTenDigits() {
        fun txt(text: String, about: String = "+19025550101") = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = about, name = "Sam")))
        assertNull(txt("Bay Heating 555-0148"))
        assertNull(txt("ok", about = "5550148"))
        assertEquals("Bay Heating", txt("Bay Heating 1-902-555-0148"))
        assertEquals("Sam", txt("ok", about = "(902) 555-0148"))
        // The contacts check stays loose: unsure means known, and nothing is shown.
        assertTrue(CallerName.same(n, "555-0148"))
    }

    @Test fun noMatch() {
        assertNull(guess(src(CallerSource.CALENDAR, CallerClue(CallerSource.CALENDAR, "Dentist\n902 555 0199", title = "Dentist"))))
        assertNull(guess())
        assertNull(CallerName.guess(null, listOf(src(CallerSource.CALENDAR, CallerClue(CallerSource.CALENDAR, "x", title = "x"))), all, { 0L }))
    }

    @Test fun numberInPeopleIsUntouched() {
        var read = false
        val r = CallerName.guess(n, listOf(CallerSource.TEXTS to { read = true; emptyList<CallerClue>() }), all, { 0L }, known = { true })
        assertNull(r)
        assertFalse(read)
    }

    @Test fun twoCandidates() {
        val a = CallerClue(CallerSource.CALENDAR, "902-555-0148", title = "Coastal Plumbing")
        val b = CallerClue(CallerSource.FILES, "Bay Heating 902-555-0148", title = "quote.pdf")
        // A tie: nothing (a wrong name is worse than none).
        assertNull(guess(src(CallerSource.CALENDAR, a), src(CallerSource.FILES, b)))
        // Found in more places wins, case aside.
        val c = CallerClue(CallerSource.TEXTS, "ok", about = n, name = "coastal plumbing")
        assertEquals("Coastal Plumbing", guess(src(CallerSource.CALENDAR, a, a.copy(title = "Coastal Plumbing")), src(CallerSource.FILES, b), src(CallerSource.TEXTS, c)))
    }

    @Test fun timeLimit() {
        var t = 0L
        val slow = CallerSource.CALENDAR to { t += CallerName.BUDGET_MS + 1; listOf(CallerClue(CallerSource.CALENDAR, "902-555-0148", title = "Coastal Plumbing")) }
        assertNull(CallerName.guess(n, listOf(slow), all, { t }))
        // Past the limit before a source: it isn't read.
        var read = false
        t = 0L
        val late = CallerSource.FILES to { read = true; emptyList<CallerClue>() }
        assertNull(CallerName.guess(n, listOf(slow, late), all, { t }))
        assertFalse(read)
        assertTrue(CallerName.BUDGET_MS < 5_000)
        // review-caller-name-b, Low 3: the clock starts with the call, so a slow contacts check counts too.
        t = 0L
        val quick = CallerSource.CALENDAR to { listOf(CallerClue(CallerSource.CALENDAR, "902-555-0148", title = "Coastal Plumbing")) }
        assertNull(CallerName.guess(n, listOf(quick), all, { t }, start = 0L, known = { t += CallerName.BUDGET_MS + 1; false }))
        t = CallerName.BUDGET_MS + 1
        assertNull(CallerName.guess(n, listOf(quick), all, { t }, start = 0L))
        assertEquals("Coastal Plumbing", CallerName.guess(n, listOf(quick), all, { t }, start = t))
    }

    @Test fun locked() {
        val cal = CallerClue(CallerSource.CALENDAR, "902-555-0148", title = "Coastal Plumbing")
        val file = CallerClue(CallerSource.FILES, "Bay Heating 902-555-0148")
        val text = CallerClue(CallerSource.TEXTS, "x", about = n, name = "Bay Heating")
        val locked = CallerName.scope(locked = true, someoneElse = false, textsOn = true, textsLocked = false)
        assertEquals(setOf(CallerSource.CALENDAR), locked.sources)
        assertFalse(locked.onLockScreen)
        // Files and texts aren't searched, so the calendar's name isn't tied.
        assertEquals("Coastal Plumbing", guess(src(CallerSource.CALENDAR, cal), src(CallerSource.FILES, file), src(CallerSource.TEXTS, text), scope = locked))
        assertTrue(CallerSource.TEXTS in CallerName.scope(locked = true, someoneElse = false, textsOn = true, textsLocked = true).sources)
        assertFalse(CallerSource.TEXTS in CallerName.scope(locked = false, someoneElse = false, textsOn = false, textsLocked = true).sources)
        val other = CallerName.scope(locked = true, someoneElse = true, textsOn = true, textsLocked = true)
        assertNull(guess(src(CallerSource.CALENDAR, cal), scope = other))
    }

    @Test fun french() {
        val fr = Langs.of(Lang.FR)
        assertEquals("Probablement Coastal Plumbing", CallerStrings.probably("name" to "Coastal Plumbing").render(fr))
        assertEquals("Probably Coastal Plumbing", CallerStrings.probably("name" to "Coastal Plumbing").en())
        assertEquals("Fournitures Côté", guess(src(CallerSource.CALENDAR, CallerClue(CallerSource.CALENDAR, "Appelle Fournitures Côté au 902-555-0148", title = "Rendez-vous"))))
        assertTrue(CallerStrings.unlock().render(fr).startsWith("Déverrouille"))
    }

    @Test fun offByDefault() {
        assertFalse(CallerNameSettings().on)
        assertFalse(CallerNameSettings.fromJson(null).on)
        assertFalse(CallerNameSettings.fromJson("{oops").on)
        assertTrue(CallerNameSettings.fromJson(CallerNameSettings(on = true).toJson()).on)
    }

    @Test fun neverSaysWhereInLogs() {
        assertEquals("CallerClue(FILES)", CallerClue(CallerSource.FILES, "Bay Heating 902-555-0148", title = "quote.pdf").toString())
    }

    /** review-caller-name-c, Medium 1: a word ending in "!" or "?" is never a name. */
    @Test fun exclaimOrQuestionIsNoName() {
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        assertNull(txt("Hi! 902-555-0148 is my new number"))
        assertNull(txt("Questions? 902-555-0148"))
        assertNull(txt("Hey! 902-555-0148"))
        assertNull(txt("Merci! 902-555-0148"))
        assertNull(txt("Allô? 902-555-0148"))
        assertEquals("Bay Heating", txt("Thanks! Bay Heating 902-555-0148"))
    }

    /** review-caller-name-c, Low 1: what follows a comma is a name only after an opener ("Hi Sam,"); never a title or a place. */
    @Test fun afterACommaOnlyAfterAnOpener() {
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        for (t in listOf("Jane Doe, Realtor 902-555-0148", "Jane Doe, Realtor, Halifax 902-555-0148")) {
            val got = txt(t)
            assertTrue("$t gave $got", got == null || got == "Jane Doe")
        }
        assertNull(txt("12 Water St, Halifax, NS 902-555-0148"))
        assertNull(txt("Bay Heating, Halifax 902-555-0148"))
        assertEquals("Bay Heating", txt("Hi Sam, Bay Heating 902-555-0148"))
        assertEquals("Plomberie Côté", txt("Salut Marie, Plomberie Côté au 902-555-0148"))
    }

    /** review-caller-name-c, Low 2: a noun such as Home, Office or New just before never leaves half a business name. */
    @Test fun nounBeforeKeepsTheNameWholeOrNothing() {
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        for ((t, whole) in listOf("Home Hardware 902-555-0148" to "Home Hardware", "Office Depot: 902-555-0148" to "Office Depot",
                "New Horizons Daycare 902-555-0148" to "New Horizons Daycare", "Bureau Gagnon au 902-555-0148" to "Bureau Gagnon", "Jane Doe, Office Manager 902-555-0148" to "Jane Doe")) {
            val got = txt(t)
            assertTrue("$t gave $got", got == null || got == whole)
        }
        // A verb or a greeting before still leaves the name.
        assertEquals("Bay Heating", txt("Call Bay Heating 902-555-0148"))
        assertEquals("Jane Doe", txt("Hi Jane Doe 902-555-0148"))
    }

    /** review-caller-name-c, Low 3: a span ending in a label or event noun (or, in French, led by one) is not a name. */
    @Test fun labelOrEventNounIsNoName() {
        fun cal(title: String) = guess(src(CallerSource.CALENDAR, CallerClue(CallerSource.CALENDAR, "902-555-0148", title = title)))
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        for (t in listOf("Customer Service: ", "Toll Free: ", "Tech Support ", "Help Desk ", "Sales Line: ", "Conference Call ",
                "Service Client : ", "Ligne Info : ", "Soutien Technique ", "Service des Ventes : ")) assertNull(t, txt("${t}902-555-0148"))
        for (t in listOf("Team Sync", "Biopsy Results", "Conference Call", "Piano Lesson", "Séance Photo", "Appel Équipe")) assertNull(t, cal(t))
        assertEquals("Coastal Plumbing", cal("Coastal Plumbing"))
        assertEquals("Nova Dental Clinic", txt("Nova Dental Clinic 902-555-0148"))
    }

    /** review-last-fixes-14, Low: after an opener's comma, a label or event noun is no name either (a scam text's shape). */
    @Test fun afterAnOpenersCommaALabelIsNoName() {
        fun txt(text: String) = guess(src(CallerSource.TEXTS, CallerClue(CallerSource.TEXTS, text, about = "+19025550101", name = "Sam")))
        for (t in listOf("Hi, Customer Service 902-555-0148", "Hi Sam, Customer Service 902-555-0148", "Bonjour, Service Client : 902-555-0148"))
            assertNull(t, txt(t))
        assertEquals("Bay Heating", txt("Hi Sam, Bay Heating 902-555-0148"))
        assertEquals("Bay Heating", txt("Hi, and Bay Heating 902-555-0148"))
    }
}
