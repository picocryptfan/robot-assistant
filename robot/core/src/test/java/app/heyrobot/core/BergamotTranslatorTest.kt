package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fast engine behind the Translator interface (docs/conversation-mode.md 4.1): what it asks :mt, and what it gives back. */
class BergamotTranslatorTest {
    private class Fake(val answer: (MtMsg.Translate) -> MtMsg.Translated) : MtTransport {
        val asked = mutableListOf<MtMsg.Translate>()
        override suspend fun translate(direction: String, lines: List<String>, deadlineMs: Long, priority: MtPriority): MtMsg.Translated {
            val t = MtMsg.Translate(direction, lines, deadlineMs, priority)
            asked += t
            return answer(t)
        }
    }

    private fun echo(f: (String) -> String?) = Fake { t -> MtMsg.Translated(t.lines.map(f), t.lines.map { 10L }, 0, 0, MtMemory.UNKNOWN) }

    private fun <T> fails(error: MtError, block: suspend () -> T) = runBlocking {
        try {
            block()
            fail("expected $error")
        } catch (e: MtException) {
            assertEquals(error, e.error)
        }
    }

    @Test fun aShortLineLosesItsFullStopOnTheWayAndGetsItBack() = runBlocking {
        val t = echo { if (it == "Oui") "Yes" else "Yes. Yes." }
        val tr = BergamotTranslator(t)
        assertEquals(MtEngine.BERGAMOT, tr.engine)
        assertEquals("Yes.", tr.translate("Oui.", "fr", "en"))
        assertEquals(MtMsg.Translate("fr-en", listOf("Oui"), MtPolicy.LINE_DEADLINE_MS, MtPriority.INTERACTIVE), t.asked.single())
        // A longer line goes as it is.
        val long = echo { "I'm going to be ten minutes late." }
        assertEquals("I'm going to be ten minutes late.", BergamotTranslator(long, 900).translate(" Je vais être en retard de dix minutes. ", "fr", "en"))
        assertEquals(MtMsg.Translate("fr-en", listOf("Je vais être en retard de dix minutes."), 900, MtPriority.INTERACTIVE), long.asked.single())
    }

    @Test fun noAnswerIsAnErrorTheCallerLogs() {
        fails(MtError.TIMEOUT) { BergamotTranslator(Fake { MtMsg.Translated(listOf(null), listOf(0), 1, 0, MtMemory.UNKNOWN) }).translate("Bonjour", "fr", "en") }
        fails(MtError.FAILED) { BergamotTranslator(Fake { MtMsg.Translated(listOf(null), listOf(5), 0, 1, MtMemory.UNKNOWN) }).translate("Bonjour", "fr", "en") }
        fails(MtError.DISCONNECTED) { BergamotTranslator(Fake { throw MtException(MtError.DISCONNECTED) }).translate("Bonjour", "fr", "en") }
        // A pair the engine doesn't have never reaches it.
        val t = echo { it }
        fails(MtError.BAD_REQUEST) { BergamotTranslator(t).translate("Hallo", "de", "en") }
        assertTrue(t.asked.isEmpty())
    }

    @Test fun aBlankLineIsNotSent() = runBlocking {
        val t = echo { "x" }
        assertEquals("", BergamotTranslator(t).translate("  ", "en", "fr"))
        assertTrue(t.asked.isEmpty())
    }

    @Test fun aBacklogGoesAsOneBatchRequestInOrder() = runBlocking {
        val t = Fake { r -> MtMsg.Translated(r.lines.map { if (it == "Non") null else it.uppercase() }, r.lines.map { 1L }, 1, 0, MtMemory.UNKNOWN) }
        val out = BergamotTranslator(t).translateAll(listOf("Bonjour tout le monde", "", "Non.", "Ça va bien."), "fr", "en", 60_000)
        assertEquals(listOf("BONJOUR TOUT LE MONDE", "", null, "ÇA VA BIEN."), out)
        assertEquals(MtMsg.Translate("fr-en", listOf("Bonjour tout le monde", "Non", "Ça va bien."), 60_000, MtPriority.BATCH), t.asked.single())
        fails(MtError.BAD_REQUEST) { BergamotTranslator(t).translateAll(List(MtPolicy.MAX_LINES + 1) { "Oui" }, "fr", "en", 1_000) }
    }
}
