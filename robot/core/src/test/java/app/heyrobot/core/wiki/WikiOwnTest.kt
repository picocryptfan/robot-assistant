// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ContactName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** H1 (review-wikipedia-p4): questions for People, memory and the phone never reach the copy; questions of fact still do. */
class WikiOwnTest {
    private val own = object : WikiTurn.Own {
        override fun names() = listOf(ContactName("s:1", "Sarah Lee"), ContactName("r:2", "Alex Martin", nicknames = listOf("Al")), ContactName("r:3", "Marie Roy"))
        override fun recalls(question: String) = "parking" in question.lowercase() || "stationnement" in question.lowercase()
    }
    private fun asks(q: String) = WikiTurn.asks(q, 1, attached = false, helperMode = false, untrusted = false, own = own)

    @Test fun peopleMemoryAndPhoneQuestionsNeverReachTheCopy() {
        val en = listOf("What's Alex's phone number?", "What's the battery at?", "When does the timer end?", "When is sarah lee's birthday?",
            "What is Alex's address?", "When is the meeting?", "Where is the parking?", "What's the alarm set for?")
        val fr = listOf("Quel est le numéro de téléphone d'Alex?", "Quand est l'anniversaire de sarah lee?", "Quel est le niveau de la batterie?", "C'est quand la minuterie?",
            "Quelle est l'adresse de Marie?", "Où est le stationnement?")
        for (q in en + fr) assertFalse(q, asks(q))
        val copy = FakeCopy("en", pages = mapOf("Sarah" to "Sarah is a name.\n\nIt is common."))
        var models = 0
        val r = runBlocking { WikiTurn.run("When is sarah lee's birthday?", 1, { listOf(copy) }, ZonedDateTime.now(), emptyList(), spoken = false, own = own) { models++; "Sarah [1]." } }
        assertEquals(WikiTurn.Result.NotAsked, r)
        assertEquals(0, copy.asked)
        assertEquals(0, models)
    }

    /** review-wikipedia-p4-b: a name anywhere, the calendar, a named appointment, a number typed in lower case. */
    @Test fun yourThingsAnywhereStayOffTheCopy() {
        for (q in listOf("What's the phone number for Alex?", "What's on the calendar?", "When is the dentist appointment?",
            "Quel est le numéro de marie?")) assertFalse(q, asks(q))
        for (q in listOf("What's the phone number for Jordan?", "Quel est le numéro de luc?")) assertFalse(q, WikiRoute.goes(q))
        for (q in listOf("Who invented the battery?", "Où se trouve La Réunion?", "What was the Halifax Explosion?", "What is the Gregorian calendar?",
            "What is the address of the White House?")) assertTrue(q, asks(q))
    }

    /** review-wikipedia-p4-b: memory keeps a question only with an item that holds its whole subject. */
    @Test fun memoryNeedsTheWholeSubject() {
        assertFalse(WikiRoute.covers("What was the Halifax Explosion?", "I live in Halifax"))
        assertTrue(WikiRoute.covers("What was the Halifax Explosion?", "Read about the Halifax Explosion of 1917"))
        assertTrue(WikiRoute.covers("Where is the parking?", "Parked on level 3 of the parking"))
        assertTrue(WikiRoute.covers("Où est le stationnement?", "Le stationnement est au niveau 3"))
    }

    /** review-wikipedia-p4-c (Low): what a kind of thing is (a, an, un, une, or none, in lower case) is the copy's, not memory's. */
    @Test fun aDefinitionIsNotMemorys() {
        assertFalse(WikiRoute.covers("What is a beagle?", "Our dog Max is a beagle"))
        assertFalse(WikiRoute.covers("What are peanuts?", "Sam is allergic to peanuts"))
        assertFalse(WikiRoute.covers("C'est quoi une batterie?", "La batterie de l'auto est neuve"))
        assertTrue(WikiRoute.covers("Who is Max?", "Our dog Max is a beagle"))
        assertTrue(WikiRoute.covers("Where is the parking?", "Parked on level 3 of the parking"))
        // « était » is a stop word folded too.
        assertTrue(WikiRoute.covers("La voiture était garée?", "La voiture est garée au niveau 3"))
    }

    /**
     * review-wikipedia-p4-c (Medium): a first name can't tell people from topics, so only a whole name beside a contact word
     * keeps the copy out (and the routers, "my", the contact words before it). The review's 30 and 30 of this job's own.
     */
    @Test fun onlyAWholeNameWithItsContactWordStaysOff() {
        val names = listOf("Jordan Blake", "Mark Chen", "April Nolan", "Victoria Hale", "Rose Tremblay", "Will Grant", "Marie Roy", "Pierre Gagnon",
            "Jean Côté", "Alex Martin").mapIndexed { i, n -> ContactName("c:$i", n) }
        val memory = listOf("Our dog Max is a beagle", "Sam is allergic to peanuts", "Parked on level 3 of the parking", "The wifi password is on the fridge",
            "My dentist is Dr. Roy")
        val people = object : WikiTurn.Own {
            override fun names() = names
            override fun recalls(question: String) = memory.any { WikiRoute.covers(question, it) }
        }
        fun asks(q: String) = WikiTurn.asks(q, 1, attached = false, helperMode = false, untrusted = false, own = people)
        val reviewFacts = listOf("Who was Mark Twain?", "What is Jordan's capital?", "Who was Queen Victoria?", "What was the Battle of Vimy Ridge in April 1917?",
            "Where is Victoria, British Columbia?", "Who was Marie Curie?", "Qu'est-ce que la pierre de Rosette?", "Où se trouve Saint-Jean?", "What is a rose?",
            "Who is Michael Jordan?", "What is a question mark?", "What will happen when the Sun dies?", "What is a beagle?", "What are peanuts?",
            "Who was Louis Riel?", "What was the Halifax Explosion?", "Who was Pierre Trudeau?", "Qui était Jean Chrétien?", "Who was Alexander Graham Bell?",
            "Who was William Shakespeare?", "What is April Fools' Day?", "Who was Rose Fortune?")
        val ownFacts = listOf("Who was Will Rogers?", "What is the Rose Bowl?", "Who was Jean Talon?", "Qui était Pierre Elliott Trudeau?",
            "Qui était Marie de l'Incarnation?", "What is Lake Victoria?", "Who was Mark Antony?", "What is the River Jordan?", "Who was Alexander the Great?",
            "What is Mark Twain's birthplace?", "When was Marie Curie born?", "What is a wild rose?", "Who was Queen Victoria's husband?",
            "C'est quoi la Saint-Jean-Baptiste?", "Qu'est-ce que le mont Royal?", "Who is Jordan Peele?", "What is free will?", "What is a peanut allergy?",
            "Où se trouve la Jordanie?", "What is the Victoria Cross?", "Qu'est-ce que Saint-Pierre-et-Miquelon?", "Who was Laura Secord?")
        for (q in reviewFacts + ownFacts) assertTrue(q, asks(q))
        // Said with a contact word, "my", or to a router: never the copy.
        val kept = listOf("What's Jordan's phone number?", "When is Mark's birthday?", "What is April's email?", "Quel est le numéro de Marie?",
            "Quelle est l'adresse de Pierre?", "When is my dentist appointment?", "What is Will's address?", "When is Alex Martin's birthday?",
            "What's Rose's email?", "Quand est l'anniversaire de jean côté?", "What is my wifi password?", "Call Pierre", "What's the number for victoria hale?")
        for (q in kept) assertFalse(q, asks(q))
        // A name alone reaches the copy now: the copy is on the phone, so a wrong answer, never a leak. Counted, not hidden.
        val reach = listOf("Where does Victoria live?", "Who is Rose?", "Who is Jean?", "Where does Mark work?").count(::asks)
        assertEquals(4, reach)
    }

    /** review-wikipedia-p4-d (Medium): a contact's first name beside a contact word stays off; a scientist's number doesn't. */
    @Test fun aFirstNameWithItsContactWordStaysOff() {
        val people = object : WikiTurn.Own {
            override fun names() = listOf(ContactName("r:2", "Alex Martin"), ContactName("r:4", "Mark Chen"))
            override fun recalls(question: String) = false
        }
        fun asks(q: String) = WikiTurn.asks(q, 1, attached = false, helperMode = false, untrusted = false, own = people)
        for (q in listOf("What's Alex's number?", "What's Alex's mobile?", "What is alex's cell number?", "What's the number for Alex?")) assertFalse(q, asks(q))
        for (q in listOf("What is Avogadro's number?", "Who was Mark Twain?", "What is Mark Twain's birthplace?", "Who was Alexander Graham Bell?")) assertTrue(q, asks(q))
    }

    @Test fun questionsOfFactStillGoToTheCopy() {
        for (q in listOf("Who was Louis Riel?", "What is a battery?", "Who invented the telephone?", "What is Avogadro's number?", "What was the Cold War?",
            "Qui était René Lévesque?", "C'est quoi une batterie?")) assertTrue(q, asks(q))
    }
}
