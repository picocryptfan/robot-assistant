// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.SummaryTodo.Kind
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SummaryTodosTest {
    private val zone = ZoneId.of("America/Halifax")
    /** The conversation: Friday 6 Nov 2026, 14:00. The summary is read the next morning. */
    private val said = ZonedDateTime.of(2026, 11, 6, 14, 0, 0, 0, zone)
    private val now = ZonedDateTime.of(2026, 11, 7, 9, 30, 0, 0, zone)
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)

    private val english = """
        Sam and Alex talked about the house and the winter.

        **Decisions**
        - Keep the old furnace one more winter [14:03].

        **To do (who, what, when)**
        - Sam: book the furnace check for the 14th [14:05]
        - Alex: call the plumber on Monday at 10am
        - Buy new filters
        - Return the ladder on November 2
        - Pay the deposit on December 3

        **Numbers and dates**
        - Furnace check on November 20.
    """.trimIndent()

    @Test fun datedTimedUndatedAndPastInEnglish() {
        val t = SummaryTodos.of(english, said, now, Lang.EN)
        assertEquals(listOf(Kind.DATED, Kind.TIMED, Kind.UNDATED, Kind.PAST, Kind.DATED), t.map { it.kind })
        assertEquals("Sam: book the furnace check", t[0].title)
        assertEquals("2026-11-14", t[0].whenArg)
        assertTrue("the 14th names no month: a guess", t[0].guess)
        assertEquals("2026-11-09 10:00", t[1].whenArg)
        assertTrue("Monday is read from the conversation's date", t[1].guess)
        assertEquals("Alex: call the plumber", t[1].title)
        assertNull(t[2].whenArg)
        assertFalse(t[2].card)
        assertFalse("a past to-do has no card", t[3].card)
        assertNull(t[3].whenArg)
        assertFalse(t[4].guess)
        assertEquals("2026-12-03", t[4].whenArg)
    }

    @Test fun theNumbersSectionIsNotAToDo() {
        assertTrue(SummaryTodos.of(english, said, now, Lang.EN).none { it.item.contains("November 20") })
    }

    @Test fun aRelativeDayIsReadFromTheConversationNotToday() {
        val t = SummaryTodos.of("To do:\n- Send Sam the quote tomorrow", said, said.plusDays(1).withHour(8), Lang.EN).single()
        assertEquals("2026-11-07", t.whenArg)
        assertTrue(t.guess)
        val line = t.line(said)!!.en()
        assertTrue(line, line.contains("Nov 7") && line.contains("all day") && line.contains("My guess") && line.contains("Nov 6"))
    }

    @Test fun anExplicitDateIsNoGuess() {
        val t = SummaryTodos.of("Next steps: pay the deposit on December 3", said, now, Lang.EN).single()
        assertEquals(Kind.DATED, t.kind)
        val line = t.line(said)!!.en()
        assertFalse(line, line.contains("guess"))
    }

    @Test fun frenchSummary() {
        val text = """
            En bref : Alex et Sam ont parlé de la maison.

            À faire (qui, quoi, quand)
            - Alex : appeler le plombier lundi à 10 h [14:06]
            - Sam : réserver l'entretien de la fournaise le 14 novembre
            - Acheter des filtres

            Questions ouvertes
            - Le prix de la pièce, le 20 novembre ?
        """.trimIndent()
        val t = SummaryTodos.of(text, said, now, Lang.FR)
        assertEquals(listOf(Kind.TIMED, Kind.DATED, Kind.UNDATED), t.map { it.kind })
        assertEquals("2026-11-09 10:00", t[0].whenArg)
        assertEquals("Alex : appeler le plombier", t[0].title)
        assertEquals("2026-11-14", t[1].whenArg)
        assertFalse(t[1].guess)
        assertEquals("Sam : réserver l'entretien de la fournaise", t[1].title)
        val line = t[0].line(said)!!.render(fr)
        assertTrue(line, line.contains("lundi") && line.contains("Mon estimation"))
    }

    @Test fun aSummaryWithoutToDosHasNone() {
        val text = "Overview: a chat about the weather.\n\nDecisions\n- None.\n\nOpen questions\n- Whether it rains on the 14th."
        assertTrue(SummaryTodos.of(text, said, now, Lang.EN).isEmpty())
        assertTrue(SummaryTodos.of("To do (who, what, when)\n- None.", said, now, Lang.EN).isEmpty())
        assertTrue(SummaryTodos.of("À faire : rien.", said, now, Lang.FR).isEmpty())
        assertTrue(SummaryTodos.of("", said, now, Lang.EN).isEmpty())
    }

    @Test fun aCardsWhenReadsBackAsTheSameStartForAddEvent() {
        for (t in SummaryTodos.of(english, said, now, Lang.EN).filter { it.card }) {
            val r = WhenParser.parse(t.whenArg!!, now, Lang.EN)
            assertTrue("${t.whenArg}: $r", r is Read.Ok)
            assertEquals(t.span!!.start.toLocalDateTime(), (r as Read.Ok).value.start.toLocalDateTime())
        }
    }
}
