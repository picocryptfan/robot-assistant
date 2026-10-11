package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The number check (docs/conversation-mode.md 7.1): what a summary says that the transcript doesn't. */
class SummaryCheckTest {
    private val transcript = listOf(
        "[14:02] Marie (fr): On se voit vendredi à 18 h 30 chez Julien? Le budget, c'est 1200 \$.",
        "[14:03] User (en): OK, I'll bring three bottles. The address is 42 Rue Saint-Denis.",
        "[14:05] Marie (fr): Parfait. Hélène vient aussi, elle arrive de Québec le 3 novembre.",
    )
    private val names = listOf("Marie")
    private val times = listOf("14:02", "14:03", "14:05")

    private fun misses(s: String) = SummaryCheck.misses(s, transcript, names, times)

    @Test fun whatTheTranscriptBacksUpPasses() {
        val summary = """
            Marie and the user plan dinner at Julien's on Friday at 18 h 30 [14:02]. The budget is 1,200 ${'$'}.
            Decisions
            - The user brings 3 bottles [14:03]; the address is 42 Rue Saint-Denis.
            - Hélène arrives from Québec on 3 November [14:05].
            Open questions
            - None.
        """.trimIndent()
        assertEquals(emptyList<SummaryMiss>(), misses(summary))
    }

    @Test fun numbersNamesAndTimesAbsentFromTheSourceAreFlagged() {
        val summary = "Marie meets Julien on Saturday at 17 h with Bob [14:04]. The budget is 1,500 ${'$'} for 4 people; the address is 42 Rue Saint-Denis."
        assertEquals(listOf(
            SummaryMiss("14:04", SummaryMiss.Kind.TIME),
            SummaryMiss("17 h", SummaryMiss.Kind.NUMBER),
            SummaryMiss("1,500 ${'$'}", SummaryMiss.Kind.NUMBER),
            SummaryMiss("4", SummaryMiss.Kind.NUMBER),
            SummaryMiss("Saturday", SummaryMiss.Kind.NAME),
            SummaryMiss("Bob", SummaryMiss.Kind.NAME),
        ), misses(summary))
        assertEquals("Robot couldn't find “17 h” in the transcript.", SummaryMiss("17 h", SummaryMiss.Kind.NUMBER).message)
        assertEquals("Robot couldn't find a line at 14:04.", SummaryMiss("14:04", SummaryMiss.Kind.TIME).message)
    }

    @Test fun peopleAndSpeakerNamesCount() {
        // "Paul" is in People: backed. A name at the start of a bullet is still checked.
        assertEquals(emptyList<SummaryMiss>(), SummaryCheck.misses("- Paul will call Marie.", transcript, names + "Paul Tremblay", times))
        assertEquals(listOf(SummaryMiss("Paul", SummaryMiss.Kind.NAME)), misses("- Paul will call Marie."))
        // Possessives and the summary's own words are fine; a common word starting a sentence is not a name.
        assertEquals(emptyList<SummaryMiss>(), misses("Overview: The user and Marie's friends. They agreed. Numbers and dates: none."))
    }
}
