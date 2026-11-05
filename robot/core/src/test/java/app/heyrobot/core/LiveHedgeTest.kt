package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.2.3 (fix-123-c, bug 4): the replies the Pixel 8 gave on the Test Bench's general-knowledge row (phone-1.2.3-b,
 * bug-everyday-b-4) keep their answer and lose the "real-time" or « accès » opening; live questions keep theirs.
 */
class LiveHedgeTest {
    /** The whole reply in one chunk, and in chunks of 1 to 7 characters as :brain streams it. */
    private fun shown(question: String, reply: String): String {
        val whole = LiveHedge(question).let { it.feed(reply) + it.flush() }
        for (n in 1..7) {
            val h = LiveHedge(question)
            val streamed = reply.chunked(n).joinToString("") { h.feed(it) } + h.flush()
            assertEquals("chunks of $n", whole, streamed)
        }
        return whole
    }

    @Test fun thePhonesEnglishAnswerLosesItsRealTimeOpening() {
        assertEquals("According to recent estimates, Portugal has a population of around 10.4 million people.",
            shown("How many people live in Portugal?",
                "I do not have real-time population data, but according to recent estimates, Portugal has a population of around 10.4 million people."))
    }

    @Test fun theFollowUpKeepsItsFigureWithoutTheOpening() {
        assertEquals("Lisbon itself has about 545,000 people, and its metropolitan area about 2.9 million.",
            shown("How many people live there?",
                "I don't have the exact, real-time population figure for Lisbon right now. Lisbon itself has about 545,000 people, and its metropolitan area about 2.9 million."))
        assertEquals("Lisbon has about 545,000 people.",
            shown("How many people live there?", "While I don't have access to up-to-date data, Lisbon has about 545,000 people."))
    }

    @Test fun thePhonesFrenchAnswerLosesItsAccesOpening() {
        assertEquals("Je peux te dire que la population du Portugal est d'environ 10,4 millions d'habitants.",
            shown("Combien de gens vivent au Portugal?",
                "Je n'ai pas accès aux données démographiques en temps réel, mais je peux te dire que la population du Portugal est d'environ 10,4 millions d'habitants."))
        assertEquals("Lisbonne compte environ 545 000 habitants.",
            shown("Combien de gens vivent là?", "Je n’ai pas accès à des données à jour. Lisbonne compte environ 545 000 habitants."))
    }

    @Test fun liveQuestionsAndLiveClausesKeepTheirWords() {
        val weather = "I don't have real-time weather data, but Halifax is usually cool in November."
        assertEquals(weather, shown("What's the weather right now?", weather))
        assertEquals(weather, shown("Should I bring a coat?", weather))
        val news = "I don't have access to current news, but you can check a news app."
        assertEquals(news, shown("Any news?", news))
        val prix = "Je n'ai pas accès aux prix en temps réel, mais le litre d'essence coûte souvent autour de 1,60 $."
        assertEquals(prix, shown("Combien coûte l'essence?", prix))
    }

    @Test fun otherRepliesPassUnchanged() {
        for (r in listOf(
            "The capital of Portugal is Lisbon. It's a beautiful city with a rich history and culture.",
            "I don't have real-time data.",
            "I can't provide medical advice, but current guidelines suggest rest.",
            "I don't know that one.",
            "La capitale du Portugal est Lisbonne.",
            "",
        )) assertEquals(r, shown("Tell me", r))
    }
}
